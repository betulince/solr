/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cluster.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.solr.common.MapWriter;

/**
 * A simplified read-only key-value structure. It is designed to support large datasets without
 * consuming a lot of memory The objective is to provide implementations that are cheap and memory
 * efficient to implement and consume. The keys are always {@link CharSequence} objects, The values
 * can be of any type
 */
@Deprecated
public interface SimpleMap<T> extends MapWriter {

  /** get a value by key. If not present , null is returned */
  T get(String key);

  @Deprecated
  default T get(String key, T def) {
    T val = get(key);
    return val == null ? def : val;
  }

  /** Navigate through all keys and values */
  @Deprecated
  void forEachEntry(BiConsumer<String, ? super T> fun);

  /**
   * iterate through all keys The default impl is suboptimal. Proper implementations must do it more
   * efficiently
   */
  @Deprecated
  default void forEachKey(Consumer<String> fun) {
    forEachEntry((k, t) -> fun.accept(k));
  }

  int size();

  /**
   * iterate through all keys but abort in between if required The default impl is suboptimal.
   * Proper implementations must do it more efficiently
   *
   * @param fun Consume each key and return a boolean to signal whether to proceed or not. If true,
   *     continue. If false stop
   */
  @Deprecated
  default void abortableForEachKey(Function<String, Boolean> fun) {
    abortableForEach((key, t) -> fun.apply(key));
  }

  /**
   * Navigate through all key-values but abort in between if required. The default impl is
   * suboptimal. Proper implementations must do it more efficiently
   *
   * @param fun Consume each entry and return a boolean to signal whether to proceed or not. If
   *     true, continue, if false stop
   */
  @Deprecated
  default void abortableForEach(BiFunction<String, ? super T, Boolean> fun) {
    forEachEntry(
        new BiConsumer<>() {
          boolean end = false;

          @Override
          public void accept(String k, T v) {
            if (end) return;
            end = fun.apply(k, v);
          }
        });
  }

  @Override
  default void writeMap(EntryWriter ew) throws IOException {
    forEachEntry(ew::putNoEx);
  }

  @Deprecated
  default Map<String, T> asMap(Map<String, T> sink) {
    forEachEntry(sink::put);
    return sink;
  }

  @Deprecated
  default Map<String, T> asMap() {
    return asMap(new LinkedHashMap<>());
  }
}
