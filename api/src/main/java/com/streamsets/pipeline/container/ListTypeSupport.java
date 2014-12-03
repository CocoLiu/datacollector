/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.container;

import com.streamsets.pipeline.api.Field;

import java.util.ArrayList;
import java.util.List;

public class ListTypeSupport extends TypeSupport<List> {

  @Override
  public List convert(Object value) {
    if (value instanceof List) {
      return (List) value;
    }
    throw new IllegalArgumentException(Utils.format("Cannot convert {} '{}' to a List",
                                                    value.getClass().getSimpleName(), value));
  }

  @Override
  public Object convert(Object value, TypeSupport targetTypeSupport) {
    if (targetTypeSupport instanceof ListTypeSupport) {
      return value;
    } else {
      throw new IllegalArgumentException(Utils.format("Cannot convert List to other type, {}", targetTypeSupport));
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object clone(Object value) {
    List List = null;
    if (value != null) {
      List = deepCopy((List<Field>)value);
    }
    return List;
  }

  private List<Field> deepCopy(List<Field> list) {
    List<Field> copy = new ArrayList<>(list.size());
    for (Field field : list) {
      Utils.checkNotNull(field, "List cannot have null elements");
      copy.add(field.clone());
    }
    return copy;
  }

}