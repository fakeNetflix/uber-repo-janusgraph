package com.thinkaurelius.titan.hadoop;

import com.thinkaurelius.titan.core.Multiplicity;
import com.thinkaurelius.titan.core.RelationType;
import com.thinkaurelius.titan.core.schema.ConsistencyModifier;
import com.thinkaurelius.titan.graphdb.internal.InternalRelationType;
import com.thinkaurelius.titan.graphdb.schema.RelationTypeDefinition;
import com.thinkaurelius.titan.graphdb.types.system.EmptyRelationType;
import com.tinkerpop.blueprints.Direction;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public abstract class FaunusRelationType extends EmptyRelationType implements InternalRelationType {

    private final RelationTypeDefinition definition;
    private final boolean isHidden;

    protected FaunusRelationType(RelationTypeDefinition def, boolean hidden) {
        this.definition = def;
        this.isHidden = hidden;
    }

    @Override
    public String name() {
        return definition.getName();
    }

    @Override
    public long longId() {
        return definition.getLongId();
    }

    @Override
    public boolean hasId() {
        return true;
    }

    @Override
    public void setId(long id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConsistencyModifier getConsistencyModifier() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Multiplicity multiplicity() {
        return definition.getMultiplicity();
    }

    @Override
    public boolean isUnidirected(Direction dir) {
        return definition.isUnidirected(dir);
    }

    @Override
    public boolean isHiddenType() {
        return isHidden;
    }

    @Override
    public String toString() {
        return definition.getName();
    }

    @Override
    public int hashCode() {
        return definition.getName().hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this==other) return true;
        else if (other==null || !(other instanceof RelationType)) return false;
        return definition.getName().equals(((RelationType)other).name());
    }

}
