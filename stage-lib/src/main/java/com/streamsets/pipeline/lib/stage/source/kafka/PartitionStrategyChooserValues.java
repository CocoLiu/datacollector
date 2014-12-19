/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.stage.source.kafka;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.streamsets.pipeline.api.ChooserValues;

import java.util.List;

public class PartitionStrategyChooserValues implements ChooserValues {

  private static final List<String> VALUES;

  static {
    VALUES = ImmutableList.copyOf(Lists.transform(ImmutableList.copyOf(PartitionStrategy.values()),
      new Function<PartitionStrategy, String>() {
        @Override
        public String apply(PartitionStrategy input) {
          return input.toString();
        }
      }));
  }

  @Override
  public List<String> getValues() {
    return VALUES;
  }

  @Override
  public List<String> getLabels() {
    return VALUES;
  }
}