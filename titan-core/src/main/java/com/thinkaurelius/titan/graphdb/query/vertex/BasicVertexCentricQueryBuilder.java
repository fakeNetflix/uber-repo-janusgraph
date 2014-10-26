package com.thinkaurelius.titan.graphdb.query.vertex;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.attribute.Cmp;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.SliceQuery;
import com.thinkaurelius.titan.graphdb.database.EdgeSerializer;
import com.thinkaurelius.titan.graphdb.internal.*;
import com.thinkaurelius.titan.graphdb.query.*;
import com.thinkaurelius.titan.graphdb.query.condition.*;
import com.thinkaurelius.titan.graphdb.relations.StandardVertexProperty;
import com.thinkaurelius.titan.graphdb.transaction.StandardTitanTx;
import com.thinkaurelius.titan.core.schema.SchemaStatus;
import com.thinkaurelius.titan.graphdb.types.system.ImplicitKey;
import com.thinkaurelius.titan.graphdb.types.system.SystemRelationType;
import com.thinkaurelius.titan.util.datastructures.ProperInterval;
import com.tinkerpop.gremlin.structure.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Builds a {@link BaseVertexQuery}, optimizes the query and compiles the result into a {@link com.thinkaurelius.titan.graphdb.query.vertex.BaseVertexCentricQuery} which
 * is then executed by one of the extending classes.
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public abstract class BasicVertexCentricQueryBuilder<Q extends BaseVertexQuery<Q>> extends BaseVertexCentricQueryBuilder<Q> {
    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(BasicVertexCentricQueryBuilder.class);

    /**
     * Transaction in which this query is executed
     */
    protected final StandardTitanTx tx;

    /**
     * Whether to query for system relations only
     */
    private boolean querySystem = false;
    /**
     Whether to query only for persisted edges, i.e. ignore any modifications to the vertex made in this transaction.
     This is achieved by using the {@link SimpleVertexQueryProcessor} for execution.
     */
    private boolean queryOnlyLoaded = false;

    /**
     * Whether to restrict this query to the specified "local" partitions in this transaction
     */
    private boolean restrict2Partitions = true;


    public BasicVertexCentricQueryBuilder(final StandardTitanTx tx) {
        super(tx);
        Preconditions.checkArgument(tx!=null);
        this.tx = tx;
    }

    @Override
    public TitanVertex getVertex(long vertexid) {
        return tx.getVertex(vertexid);
    }

    /* ---------------------------------------------------------------
     * Query Construction
	 * ---------------------------------------------------------------
	 */

    /**
     * Removes any query partition restriction for this query
     *
     * @return
     */
    public Q noPartitionRestriction() {
        this.restrict2Partitions = false;
        return getThis();
    }

    /**
     * Restricts the result set of this query to only system types.
     * @return
     */
    public Q system() {
        this.querySystem = true;
        return getThis();
    }

    /**
     * Calling this method will cause this query to only included loaded (i.e. unmodified) relations in the
     * result set.
     * @return
     */
    public Q queryOnlyLoaded() {
        queryOnlyLoaded=true;
        return getThis();
    }




    /* ---------------------------------------------------------------
     * Inspection Methods
	 * ---------------------------------------------------------------
	 */

    private boolean hasAllCanonicalTypes() {
        for (String typeName : types) {
            InternalRelationType type = QueryUtil.getType(tx, typeName);
            if (type==null) continue;
            if (!type.multiplicity().isUnique(dir)) return false;
        }
        return true;
    }

    /* ---------------------------------------------------------------
     * Utility Methods
	 * ---------------------------------------------------------------
	 */

    protected static Iterable<TitanVertex> edges2Vertices(final Iterable<TitanEdge> edges, final TitanVertex other) {
        return Iterables.transform(edges, new Function<TitanEdge, TitanVertex>() {
            @Nullable
            @Override
            public TitanVertex apply(@Nullable TitanEdge titanEdge) {
                return titanEdge.otherVertex(other);
            }
        });
    }

    protected VertexList edges2VertexIds(final Iterable<TitanEdge> edges, final TitanVertex other) {
        VertexArrayList vertices = new VertexArrayList(tx);
        for (TitanEdge edge : edges) vertices.add(edge.otherVertex(other));
        return vertices;
    }

    /* ---------------------------------------------------------------
     * Query Execution (Helper methods)
	 * ---------------------------------------------------------------
	 */

    /**
     * If {@link #isImplicitKeyQuery(com.thinkaurelius.titan.graphdb.internal.RelationCategory)} is true,
     * this method provides the result set for the query based on the evaluation of the {@link ImplicitKey}.
     * </p>
     * Handling of implicit keys is completely distinct from "normal" query execution and handled extra
     * for completeness reasons.
     *
     * @param v
     * @return
     */
    protected Iterable<TitanRelation> executeImplicitKeyQuery(InternalVertex v) {
        assert isImplicitKeyQuery(RelationCategory.PROPERTY);
        if (dir==Direction.IN || limit<1) return ImmutableList.of();
        ImplicitKey key = (ImplicitKey)tx.getRelationType(types[0]);
        return ImmutableList.of((TitanRelation)new StandardVertexProperty(0,key,v,key.computeProperty(v), v.isNew()?ElementLifeCycle.New:ElementLifeCycle.Loaded));
    }

    protected interface ResultConstructor<Q> {

        Q getResult(InternalVertex v, BaseVertexCentricQuery bq);

        Q emptyResult();

    }

    protected class RelationConstructor implements ResultConstructor<Iterable<? extends TitanRelation>> {

        @Override
        public Iterable<? extends TitanRelation> getResult(InternalVertex v, BaseVertexCentricQuery bq) {
            return executeRelations(v,bq);
        }

        @Override
        public Iterable<? extends TitanRelation> emptyResult() {
            return Collections.EMPTY_LIST;
        }

    }

    protected class VertexConstructor implements ResultConstructor<Iterable<TitanVertex>> {

        @Override
        public Iterable<TitanVertex> getResult(InternalVertex v, BaseVertexCentricQuery bq) {
            return executeVertices(v,bq);
        }

        @Override
        public Iterable<TitanVertex> emptyResult() {
            return Collections.EMPTY_LIST;
        }

    }

    protected class VertexIdConstructor implements ResultConstructor<VertexList> {

        @Override
        public VertexList getResult(InternalVertex v, BaseVertexCentricQuery bq) {
            return executeVertexIds(v,bq);
        }

        @Override
        public VertexList emptyResult() {
            return new VertexArrayList(tx);
        }

    }

    protected List<InternalVertex> allRepresentatives(InternalVertex partitionedVertex) {
        if (hasAllCanonicalTypes()) {
            return ImmutableList.of(tx.getCanonicalVertex(partitionedVertex));
        }
        return Arrays.asList(tx.getAllRepresentatives(partitionedVertex,restrict2Partitions));
    }


    protected final boolean isPartitionedVertex(InternalVertex vertex) {
        return tx.isPartitionedVertex(vertex);
    }

    protected boolean useSimpleQueryProcessor(BaseVertexCentricQuery query, InternalVertex... vertices) {
        assert vertices.length>0;
        if (!query.isSimple()) return false;
        if (queryOnlyLoaded) return true;
        for (InternalVertex vertex : vertices) if (!vertex.isLoaded()) return false;
        return true;
    }

    protected Iterable<TitanRelation> executeRelations(InternalVertex vertex, BaseVertexCentricQuery baseQuery) {
        if (isPartitionedVertex(vertex)) {
            if (!hasAllCanonicalTypes()) {
                InternalVertex[] representatives = tx.getAllRepresentatives(vertex,restrict2Partitions);
                Iterable<TitanRelation> merge = null;

                for (InternalVertex rep : representatives) {
                    Iterable<TitanRelation> iter = executeIndividualRelations(rep,baseQuery);
                    if (merge==null) merge = iter;
                    else merge = ResultMergeSortIterator.mergeSort(merge,iter,(Comparator)orders,false);
                }
                return ResultSetIterator.wrap(merge,baseQuery.getLimit());
            } else vertex = tx.getCanonicalVertex(vertex);
        }
        return executeIndividualRelations(vertex,baseQuery);
    }

    private Iterable<TitanRelation> executeIndividualRelations(InternalVertex vertex, BaseVertexCentricQuery baseQuery) {
        VertexCentricQuery query = constructQuery(vertex, baseQuery);
        if (useSimpleQueryProcessor(query,vertex)) return new SimpleVertexQueryProcessor(query,tx).relations();
        else return new QueryProcessor<VertexCentricQuery,TitanRelation,SliceQuery>(query, tx.edgeProcessor);
    }

    public Iterable<TitanVertex> executeVertices(InternalVertex vertex, BaseVertexCentricQuery baseQuery) {
        if (isPartitionedVertex(vertex)) {
            //If there is a sort order, we need to first merge the relations (and sort) and then compute vertices
            if (!orders.isEmpty()) return edges2VertexIds((Iterable) executeRelations(vertex,baseQuery), vertex);

            if (!hasAllCanonicalTypes()) {
                InternalVertex[] representatives = tx.getAllRepresentatives(vertex,restrict2Partitions);
                Iterable<TitanVertex> merge = null;

                for (InternalVertex rep : representatives) {
                    Iterable<TitanVertex> iter = executeIndividualVertices(rep,baseQuery);
                    if (merge==null) merge = iter;
                    else merge = ResultMergeSortIterator.mergeSort(merge,iter,VertexArrayList.VERTEX_ID_COMPARATOR,false);
                }
                return ResultSetIterator.wrap(merge,baseQuery.getLimit());
            } else vertex = tx.getCanonicalVertex(vertex);
        }
        return executeIndividualVertices(vertex,baseQuery);
    }

    private Iterable<TitanVertex> executeIndividualVertices(InternalVertex vertex, BaseVertexCentricQuery baseQuery) {
        VertexCentricQuery query = constructQuery(vertex, baseQuery);
        if (useSimpleQueryProcessor(query, vertex)) return new SimpleVertexQueryProcessor(query,tx).vertexIds();
        else return edges2Vertices((Iterable) executeIndividualRelations(vertex,baseQuery), query.getVertex());
    }

    public VertexList executeVertexIds(InternalVertex vertex, BaseVertexCentricQuery baseQuery) {
        if (isPartitionedVertex(vertex)) {
            //If there is a sort order, we need to first merge the relations (and sort) and then compute vertices
            if (!orders.isEmpty()) return edges2VertexIds((Iterable) executeRelations(vertex,baseQuery), vertex);

            if (!hasAllCanonicalTypes()) {
                InternalVertex[] representatives = tx.getAllRepresentatives(vertex,restrict2Partitions);
                VertexListInternal merge = null;

                for (InternalVertex rep : representatives) {
                    if (merge!=null && merge.size()>=baseQuery.getLimit()) break;
                    VertexList vlist = executeIndividualVertexIds(rep,baseQuery);
                    if (merge==null) merge = (VertexListInternal)vlist;
                    else merge.addAll(vlist);
                }
                if (merge.size()>baseQuery.getLimit()) merge = (VertexListInternal)merge.subList(0,baseQuery.getLimit());
                return merge;
            } else vertex = tx.getCanonicalVertex(vertex);
        }
        return executeIndividualVertexIds(vertex,baseQuery);
    }

    private VertexList executeIndividualVertexIds(InternalVertex vertex, BaseVertexCentricQuery baseQuery) {
        VertexCentricQuery query = constructQuery(vertex, baseQuery);
        if (useSimpleQueryProcessor(query, vertex)) return new SimpleVertexQueryProcessor(query,tx).vertexIds();
        return edges2VertexIds((Iterable) executeIndividualRelations(vertex,baseQuery), vertex);
    }


    /* ---------------------------------------------------------------
     * Query Optimization and Construction
	 * ---------------------------------------------------------------
	 */

    private static final int HARD_MAX_LIMIT   = 300000;


    @Override
    public QueryDescription describeForEdges() {
        return describe(1, RelationCategory.EDGE);
    }

    @Override
    public QueryDescription describeForProperties() {
        return describe(1,RelationCategory.PROPERTY);
    }

    public QueryDescription describeForRelations() {
        return describe(1,RelationCategory.RELATION);
    }

    protected QueryDescription describe(int numVertices, RelationCategory returnType) {
        return new StandardQueryDescription(numVertices,constructQuery(returnType));
    }

    /**
     * Constructs a {@link VertexCentricQuery} for this query builder. The query construction and optimization
     * logic is taken from {@link #constructQuery(com.thinkaurelius.titan.graphdb.internal.RelationCategory)}
     * This method only adds the additional conditions that are based on the base vertex.
     *
     * @param vertex for which to construct this query
     * @param baseQuery as constructed by {@link #constructQuery(com.thinkaurelius.titan.graphdb.internal.RelationCategory)}
     * @return
     */
    protected VertexCentricQuery constructQuery(InternalVertex vertex, BaseVertexCentricQuery baseQuery) {
        Condition<TitanRelation> condition = baseQuery.getCondition();
        if (!baseQuery.isEmpty()) {
            //Add adjacent-vertex and direction related conditions; copy conditions to so that baseQuery does not change
            And<TitanRelation> newcond = new And<TitanRelation>();
            if (condition instanceof And) newcond.addAll((And) condition);
            else newcond.add(condition);

            newcond.add(new DirectionCondition<TitanRelation>(vertex,dir));
            if (adjacentVertex != null)
                newcond.add(new IncidenceCondition<TitanRelation>(vertex,adjacentVertex));

            condition = newcond;
        }
        VertexCentricQuery query = new VertexCentricQuery(vertex, condition, baseQuery.getDirection(), baseQuery.getQueries(),baseQuery.getOrders(), baseQuery.getLimit());
        Preconditions.checkArgument(!queryOnlyLoaded || query.isSimple(),"Query-only-loaded only works on simple queries");
        return query;
    }


    protected BaseVertexCentricQuery constructQuery(RelationCategory returnType) {
        assert returnType != null;
        Preconditions.checkArgument(adjacentVertex==null || returnType == RelationCategory.EDGE,"Vertex constraints only apply to edges");
        if (limit <= 0)
            return BaseVertexCentricQuery.emptyQuery();

        //Prepare direction
        if (returnType == RelationCategory.PROPERTY) {
            if (dir == Direction.IN)
                return BaseVertexCentricQuery.emptyQuery();
            dir = Direction.OUT;
        }
        //Prepare order
        orders.makeImmutable();
        assert orders.hasCommonOrder();

        //Prepare constraints
        And<TitanRelation> conditions = QueryUtil.constraints2QNF(tx, constraints);
        if (conditions == null)
            return BaseVertexCentricQuery.emptyQuery();

        //Don't be smart with query limit adjustments - it just messes up the caching layer and penalizes when appropriate limits are set by the user!
        int sliceLimit = limit;

        //Construct (optimal) SliceQueries
        EdgeSerializer serializer = tx.getEdgeSerializer();
        List<BackendQueryHolder<SliceQuery>> queries;
        if (!hasTypes()) {
            BackendQueryHolder<SliceQuery> query = new BackendQueryHolder<SliceQuery>(serializer.getQuery(returnType, querySystem),
                    ((dir == Direction.BOTH || (returnType == RelationCategory.PROPERTY && dir == Direction.OUT))
                            && !conditions.hasChildren()), orders.isEmpty(), null);
            if (sliceLimit!=Query.NO_LIMIT && sliceLimit<Integer.MAX_VALUE/3) {
                //If only one direction is queried, ask for twice the limit from backend since approximately half will be filtered
                if (dir != Direction.BOTH && (returnType == RelationCategory.EDGE || returnType == RelationCategory.RELATION))
                    sliceLimit *= 2;
            }
            query.getBackendQuery().setLimit(computeLimit(conditions.size(),sliceLimit));
            queries = ImmutableList.of(query);
            conditions.add(returnType);
            conditions.add(new VisibilityFilterCondition<TitanRelation>(  //Need this to filter out newly created hidden relations in the transaction
                    querySystem? VisibilityFilterCondition.Visibility.SYSTEM: VisibilityFilterCondition.Visibility.NORMAL));
        } else {
            Set<RelationType> ts = new HashSet<RelationType>(types.length);
            queries = new ArrayList<BackendQueryHolder<SliceQuery>>(types.length + 2);
            Map<RelationType,ProperInterval> intervalConstraints = new HashMap<RelationType, ProperInterval>(conditions.size());
            final boolean isIntervalFittedConditions = compileConstraints(conditions,intervalConstraints);
            for (ProperInterval pint : intervalConstraints.values()) { //Check if one of the constraints leads to an empty result set
                if (pint.isEmpty()) return BaseVertexCentricQuery.emptyQuery();
            }

            for (String typeName : types) {
                InternalRelationType type = QueryUtil.getType(tx, typeName);
                if (type==null) continue;
                Preconditions.checkArgument(!querySystem || (type instanceof SystemRelationType),"Can only query for system types: %s",type);
                if (type instanceof ImplicitKey) throw new UnsupportedOperationException("Implicit types are not supported in complex queries: "+type);
                ts.add(type);

                Direction typeDir = dir;
                if (type.isPropertyKey()) {
                    if (returnType == RelationCategory.EDGE)
                        throw new IllegalArgumentException("Querying for edges but including a property key: " + type.name());
                    returnType = RelationCategory.PROPERTY;
                    typeDir = Direction.OUT;
                }
                if (type.isEdgeLabel()) {
                    if (returnType == RelationCategory.PROPERTY)
                        throw new IllegalArgumentException("Querying for properties but including an edge label: " + type.name());
                    returnType = RelationCategory.EDGE;
                    if (!type.isUnidirected(Direction.BOTH)) {
                        //Make sure unidirectionality lines up
                        if (typeDir==Direction.BOTH) {
                            if (type.isUnidirected(Direction.OUT)) typeDir=Direction.OUT;
                            else typeDir=Direction.IN;
                        } else if (!type.isUnidirected(typeDir)) continue; //Directions are incompatible
                    }
                }


                if (type.isEdgeLabel() && typeDir==Direction.BOTH && intervalConstraints.isEmpty() && orders.isEmpty()) {
                    //TODO: This if-condition is a little too restrictive - we also want to include those cases where there
                    // ARE intervalConstraints or orders but those cannot be covered by any sort-keys
                    SliceQuery q = serializer.getQuery(type, typeDir, null);
                    q.setLimit(sliceLimit);
                    queries.add(new BackendQueryHolder<SliceQuery>(q, isIntervalFittedConditions, true, null));
                } else {
                    //Optimize for each direction independently
                    Direction[] dirs = {typeDir};
                    if (typeDir == Direction.BOTH) {
                        if (type.isEdgeLabel())
                            dirs = new Direction[]{Direction.OUT, Direction.IN};
                        else
                            dirs = new Direction[]{Direction.OUT}; //property key
                    }

                    for (Direction direction : dirs) {
                        /*
                        Find best scoring relation type to answer this query with. We score each candidate by the number
                        of conditions that each sort-keys satisfy. Equality conditions score higher than interval conditions
                        since they are more restrictive. We assign additional points if the sort key satisfies the order
                        of this query.
                        */
                        InternalRelationType bestCandidate = null;
                        int bestScore = Integer.MIN_VALUE;
                        boolean bestCandidateSupportsOrder = false;
                        for (InternalRelationType candidate : type.getRelationIndexes()) {
                            //Filter out those that don't apply
                            if (!candidate.isUnidirected(Direction.BOTH) && !candidate.isUnidirected(direction)) continue;
                            if (candidate.getStatus()!= SchemaStatus.ENABLED) continue;

                            boolean supportsOrder = orders.isEmpty()?true:orders.getCommonOrder()==candidate.getSortOrder();
                            int currentOrder = 0;

                            int score = 0;
                            RelationType[] extendedSortKey = getExtendedSortKey(candidate,direction,tx);

                            for (int i=0;i<extendedSortKey.length;i++) {
                                RelationType keyType = extendedSortKey[i];
                                if (currentOrder<orders.size() && orders.getKey(currentOrder).equals(keyType)) currentOrder++;

                                ProperInterval interval = intervalConstraints.get(keyType);
                                if (interval==null || !interval.isPoint()) {
                                    if (interval!=null) score+=1;
                                    break;
                                } else {
                                    assert interval.isPoint();
                                    score+=5;
                                }
                            }
                            if (supportsOrder && currentOrder==orders.size()) score+=3;
                            if (score>bestScore) {
                                bestScore=score;
                                bestCandidate=candidate;
                                bestCandidateSupportsOrder=supportsOrder && currentOrder==orders.size();
                            }
                        }
                        Preconditions.checkArgument(bestCandidate!=null,"Current graph schema does not support the specified query constraints for type: %s",type.name());

                        //Construct sort key constraints for the best candidate and then serialize into a SliceQuery
                        //that is wrapped into a BackendQueryHolder
                        RelationType[] extendedSortKey = getExtendedSortKey(bestCandidate,direction,tx);
                        EdgeSerializer.TypedInterval[] sortKeyConstraints = new EdgeSerializer.TypedInterval[extendedSortKey.length];
                        int coveredTypes = 0;
                        for (int i = 0; i < extendedSortKey.length; i++) {
                            RelationType keyType = extendedSortKey[i];
                            ProperInterval interval = intervalConstraints.get(keyType);
                            if (interval!=null) {
                                sortKeyConstraints[i]=new EdgeSerializer.TypedInterval((InternalRelationType) keyType,interval);
                                coveredTypes++;
                            }
                            if (interval==null || !interval.isPoint()) break;
                        }

                        boolean isFitted = isIntervalFittedConditions && coveredTypes==intervalConstraints.size();
                        SliceQuery q = serializer.getQuery(bestCandidate, direction, sortKeyConstraints);
                        q.setLimit(computeLimit(intervalConstraints.size()-coveredTypes, sliceLimit));
                        queries.add(new BackendQueryHolder<SliceQuery>(q, isFitted, bestCandidateSupportsOrder, null));


                    }
                }
            }
            if (queries.isEmpty())
                return BaseVertexCentricQuery.emptyQuery();

            conditions.add(getTypeCondition(ts));
        }

        return new BaseVertexCentricQuery(QueryUtil.simplifyQNF(conditions), dir, queries, orders, limit);
    }

    /**
     * Returns the extended sort key of the given type. The extended sort key extends the type's primary sort key
     * by ADJACENT_ID and ID depending on the multiplicity of the type in the given direction.
     * It also converts the type ids to actual types.
     *
     * @param type
     * @param dir
     * @param tx
     * @return
     */
    private static RelationType[] getExtendedSortKey(InternalRelationType type, Direction dir, StandardTitanTx tx) {
        int additional = 0;
        if (!type.multiplicity().isUnique(dir)) {
            if (!type.multiplicity().isConstrained()) additional++;
            if (type.isEdgeLabel()) additional++;
        }
        RelationType[] entireKey = new RelationType[type.getSortKey().length+additional];
        int i;
        for (i=0;i<type.getSortKey().length;i++) {
            entireKey[i]=tx.getExistingRelationType(type.getSortKey()[i]);
        }
        if (type.isEdgeLabel() && !type.multiplicity().isUnique(dir)) entireKey[i++]=ImplicitKey.ADJACENT_ID;
        if (!type.multiplicity().isConstrained()) entireKey[i++]=ImplicitKey.TITANID;
        return entireKey;
    }

    /**
     * Converts the constraint conditions of this query into a constraintMap which is passed as an argument.
     * If all the constraint conditions could be accounted for in the constraintMap, this method returns true, else -
     * if some constraints cannot be captured in an interval - it returns false to indicate that further in-memory filtering
     * will be necessary.
     * </p>
     * This constraint map is used in constructing the SliceQueries and query optimization since this representation
     * is easier to handle.
     *
     * @param conditions
     * @param constraintMap
     * @return
     */
    private boolean compileConstraints(And<TitanRelation> conditions, Map<RelationType,ProperInterval> constraintMap) {
        boolean isFitted = true;
        for (Condition<TitanRelation> condition : conditions.getChildren()) {
            if (!(condition instanceof PredicateCondition)) continue; //TODO: Should we optimize OR clauses?
            PredicateCondition<RelationType, TitanRelation> atom = (PredicateCondition)condition;
            RelationType type = atom.getKey();
            assert type!=null;
            ProperInterval pi = constraintMap.get(type);
            if (pi==null) {
                pi = new ProperInterval();
                constraintMap.put(type,pi);
            }
            boolean fittedSub = compileConstraint(pi,type,atom.getPredicate(),atom.getValue());
            isFitted = isFitted && fittedSub;
        }
        if (adjacentVertex!=null) {
            if (adjacentVertex.hasId()) constraintMap.put(ImplicitKey.ADJACENT_ID,new ProperInterval(adjacentVertex.longId()));
            else isFitted=false;
        }
        return isFitted;
    }

    private static boolean compileConstraint(ProperInterval pint, RelationType type, TitanPredicate predicate, Object value) {
        if (predicate instanceof Cmp) {
            Cmp cmp = (Cmp)predicate;
            if (cmp==Cmp.EQUAL) {
                if (value==null) return false;
                boolean fitted=pint.contains(value);
                pint.setPoint(value);
                return fitted;
            }
            if (cmp==Cmp.NOT_EQUAL) {
                return false;
            }
            assert value!=null && value instanceof Comparable;
            Comparable v = (Comparable)value;
            switch ((Cmp) predicate) {
                case LESS_THAN:
                    if (pint.getEnd() == null || v.compareTo(pint.getEnd()) <= 0) {
                        pint.setEnd(v);
                        pint.setEndInclusive(false);
                    }
                    return true;
                case LESS_THAN_EQUAL:
                    if (pint.getEnd() == null || v.compareTo(pint.getEnd()) < 0) {
                        pint.setEnd(v);
                        pint.setEndInclusive(true);
                    }
                    return true;
                case GREATER_THAN:
                    if (pint.getStart() == null || v.compareTo(pint.getStart()) >= 0) {
                        pint.setStart(v);
                        pint.setStartInclusive(false);
                    }
                    return true;
                case GREATER_THAN_EQUAL:
                    if (pint.getStart() == null || v.compareTo(pint.getStart()) > 0) {
                        pint.setStart(v);
                        pint.setStartInclusive(true);
                    }
                    return true;
                default: throw new AssertionError();
            }
        } else return false;
    }

    /**
     * Constructs a condition that is equivalent to the type constraints of this query if there are any.
     *
     * @param types
     * @return
     */
    private static Condition<TitanRelation> getTypeCondition(Set<RelationType> types) {
        assert !types.isEmpty();
        if (types.size() == 1)
            return new RelationTypeCondition<TitanRelation>(types.iterator().next());

        Or<TitanRelation> typeCond = new Or<TitanRelation>(types.size());
        for (RelationType type : types)
            typeCond.add(new RelationTypeCondition<TitanRelation>(type));

        return typeCond;
    }

    /**
     * Updates a given user limit based on the number of conditions that can not be fulfilled by the backend query, i.e. the query
     * is not fitted and these remaining conditions must be enforced by filtering in-memory. By filtering in memory, we will discard
     * results returned from the backend and hence we should increase the limit to account for this "waste" in order to not have
     * to adjust the limit too often in {@link com.thinkaurelius.titan.graphdb.query.LimitAdjustingIterator}.
     *
     * @param remainingConditions
     * @param baseLimit
     * @return
     */
    private int computeLimit(int remainingConditions, int baseLimit) {
        if (baseLimit==Query.NO_LIMIT) return baseLimit;
        assert baseLimit>0;
        baseLimit = Math.max(baseLimit,Math.min(HARD_MAX_LIMIT, QueryUtil.adjustLimitForTxModifications(tx, remainingConditions, baseLimit)));
        assert baseLimit>0;
        return baseLimit;
    }


}
