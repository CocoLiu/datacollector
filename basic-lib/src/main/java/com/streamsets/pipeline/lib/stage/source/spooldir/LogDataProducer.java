/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.stage.source.spooldir;

import com.codahale.metrics.Counter;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.lib.io.CountingReader;
import com.streamsets.pipeline.lib.io.OverrunLineReader;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class LogDataProducer implements DataProducer {
  private final static Logger LOG = LoggerFactory.getLogger(LogDataProducer.class);

  private static final String LINE = "line";
  private static final String TRUNCATED = "truncated";

  private final Source.Context context;
  private final int maxLogLineLength;
  private final StringBuilder line;
  private final Counter linesOverMaxLengthCounter;

  public LogDataProducer(Source.Context context, int maxLogLineLength) {
    this.context = context;
    this.maxLogLineLength = maxLogLineLength;
    line = new StringBuilder(maxLogLineLength);
    linesOverMaxLengthCounter = context.createCounter("linesOverMaxLen");
  }

  @Override
  public long produce(File file, long offset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
    String sourceFile = file.getName();
    try (CountingReader reader = new CountingReader(new FileReader(file))) {
      IOUtils.skipFully(reader, offset);
      OverrunLineReader lineReader = new OverrunLineReader(reader, maxLogLineLength);
      return produce(sourceFile, offset, lineReader, maxBatchSize, batchMaker);
    } catch (IOException ex) {
      throw new StageException(null, ex.getMessage(), ex);
    }
  }

  protected long produce(String sourceFile, long offset, OverrunLineReader lineReader, int maxBatchSize,
      BatchMaker batchMaker) throws IOException {
    for (int i = 0; i < maxBatchSize; i++) {
      line.setLength(0);
      int len = lineReader.readLine(line);
      if (len > maxLogLineLength) {
        linesOverMaxLengthCounter.inc();
        LOG.warn("Log line exceeds maximum length '{}', log file '{}', line starts at offset '{}'", maxLogLineLength,
                 sourceFile, offset);
      }
      if (len > -1) {
        Record record = context.createRecord(Utils.format("file={} offset={}", sourceFile, offset));
        Map<String, Field> map = new LinkedHashMap<>();
        map.put(LINE, Field.create(line.toString()));
        map.put(TRUNCATED, Field.create(len > maxLogLineLength));
        record.set(Field.create(map));
        batchMaker.addRecord(record);
        offset = lineReader.getCount();
      } else {
        offset = -1;
        break;
      }
    }
    return offset;
  }

}