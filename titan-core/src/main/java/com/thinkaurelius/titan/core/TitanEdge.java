
package com.thinkaurelius.titan.core;

import com.tinkerpop.gremlin.structure.Direction;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Vertex;

/**
 * A TitanEdge connects two {@link TitanVertex}. It extends the functionality provided by Blueprint's {@link Edge} and
 * is a special case of a {@link TitanRelation}.
 *
 * @author Matthias Br&ouml;cheler (http://www.matthiasb.com)
 * @see Edge
 * @see TitanRelation
 * @see EdgeLabel
 */
public interface TitanEdge extends TitanRelation, Edge {

    /**
     * Returns the edge label of this edge
     *
     * @return edge label of this edge
     */
    public default EdgeLabel edgeLabel() {
        assert getType() instanceof EdgeLabel;
        return (EdgeLabel)getType();
    }

    /**
     * Returns the vertex for the specified direction.
     * The direction cannot be Direction.BOTH.
     *
     * @return the vertex for the specified direction
     */
    public TitanVertex vertex(Direction dir);

    /**
     * Returns the vertex at the opposite end of the edge.
     *
     * @param vertex vertex on which this edge is incident
     * @return The vertex at the opposite end of the edge.
     * @throws InvalidElementException if the edge is not incident on the specified vertex
     */
    public TitanVertex otherVertex(Vertex vertex);


}
