
package com.thinkaurelius.titan.core;

import com.tinkerpop.gremlin.structure.VertexProperty;

/**
 * TitanProperty is a {@link TitanRelation} connecting a vertex to a value.
 * TitanProperty extends {@link TitanRelation}, with methods for retrieving the property's value and key.
 *
 * @author Matthias Br&ouml;cheler (me@matthiasb.com);
 * @see TitanRelation
 * @see PropertyKey
 */
public interface TitanVertexProperty<V> extends TitanRelation, VertexProperty<V>, TitanProperty<V> {

    /**
     * Returns the vertex on which this property is incident.
     *
     * @return The vertex of this property.
     */
    @Override
    public TitanVertex getElement();

    /**
     * Returns the {@link PropertyKey} for this property
     * 
     * @return
     */
    public default PropertyKey propertyKey() {
        return (PropertyKey)getType();
    }

}
