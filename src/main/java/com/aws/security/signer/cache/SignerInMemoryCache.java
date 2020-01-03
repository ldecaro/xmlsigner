/*
 * Copyright 2011-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.aws.security.signer.cache;

import java.util.ArrayList;

import org.apache.commons.collections4.MapIterator;
import org.apache.commons.collections4.map.LRUMap;
 
/**
 * @author lddecaro@amazon.com
 */
 
public class SignerInMemoryCache<K, T> {
 
	private LRUMap<K, CacheObject> signerCache;
    private long ttl;
 
    /**
     * 
     * @param chosenTTL. ttl in seconds for the objects in the cache.
     * @param scanInterval. Interval of each scan to verify objects for ttl expiration.
     * @param maxItems. Max Items in the cache.
     * @author lddecaro@amazon.com
     */
    public SignerInMemoryCache(long chosenTTL, final long scanInterval, int maxItems) {
    	
        this.ttl = chosenTTL * 1000;
 
        signerCache = new LRUMap<K, CacheObject>(maxItems);
 
        if (ttl > 0 && scanInterval > 0) {
 
            Thread t = new Thread(()-> {                
                    while (true) {
                        try {
                            Thread.sleep(scanInterval * 1000);
                        } catch (InterruptedException ex) {
                        }
                        cleanup();
                    }                
            });
 
            t.setDaemon(true);
            t.start();
        }
    }
 
    public void put(K key, T value) {
        synchronized (signerCache) {
            signerCache.put(key, new CacheObject(value));
        }
    }
 
    public T get(K key) {
        synchronized (signerCache) {
            CacheObject c = (CacheObject) signerCache.get(key);
            if (c == null)
                return null;
            else {
                c.setLastAccessed(System.currentTimeMillis());
                return c.getValue();
            }
        }
    }
 
    public void remove(K key) {
        synchronized (signerCache) {
            signerCache.remove(key);
        }
    }
 
    public int size() {
        synchronized (signerCache) {
            return signerCache.size();
        }
    }
 
    public void cleanup() {
 
        long now = System.currentTimeMillis();
        ArrayList<K> deleteKey = null;
 
        synchronized (signerCache) {
            MapIterator<K, CacheObject> itr = signerCache.mapIterator();
 
            deleteKey = new ArrayList<K>((signerCache.size() / 2) + 1);
            K key = null;
            CacheObject c = null;
 
            while (itr.hasNext()) {
                key = (K) itr.next();
                c = (CacheObject) itr.getValue();
 
                if (c != null && (now > (ttl + c.lastAccessed))) {
                    deleteKey.add(key);
                }
            }
        }
        for (K key : deleteKey) {
            synchronized (signerCache) {
                signerCache.remove(key);
            }
            Thread.yield();
        }
    }
    
    protected class CacheObject {
    	
        private long lastAccessed = System.currentTimeMillis();
        private T value;
 
        protected CacheObject(T value) {
            this.value = value;
        }

		public long getLastAccessed() {
			return lastAccessed;
		}

		public void setLastAccessed(long lastAccessed) {
			this.lastAccessed = lastAccessed;
		}

		public T getValue() {
			return value;
		}

		public void setValue(T value) {
			this.value = value;
		}
    }
}
