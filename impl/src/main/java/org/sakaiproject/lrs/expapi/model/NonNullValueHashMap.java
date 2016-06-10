/**
 * Copyright 2013 Unicon (R) Licensed under the
 * Educational Community License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.sakaiproject.lrs.expapi.model;

import java.util.HashMap;

/**
 * @param <K>
 * @param <V>
 * 
 * @author Robert Long (rlong @ unicon.net)
 */
public class NonNullValueHashMap<K, V> extends HashMap<K, V> {
    private static final long serialVersionUID = 1L;

    /**
     * If the key or value is null, we don't add it to the map. This allows us to "quietly" ignore storing nulls
     * 
     * @see java.util.HashMap#put(java.lang.Object, java.lang.Object)
     */
    @Override
    public V put(K key, V value) {
        return (null == key || null == value) ? null : super.put(key, value);
    }

}
