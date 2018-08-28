/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.util;

import java.io.ByteArrayInputStream;

import com.esotericsoftware.kryo.*;
import com.esotericsoftware.shaded.org.objenesis.strategy.*;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.apache.hadoop.yarn.api.records.timeline.*;

/**
 * Created by wankun on 2018/8/25.
 */
public class KryoSerializer {
  private static final Log LOG = LogFactory.getLog(KryoSerializer.class);

  public static ThreadLocal<Kryo> timelineSerializationKryo = new ThreadLocal<Kryo>() {
    @Override protected synchronized Kryo initialValue() {
      Kryo kryo = new Kryo();
      kryo.setClassLoader(Thread.currentThread().getContextClassLoader());
      kryo.register(TimelineEntities.class);
      kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
      return kryo;
    }

    ;
  };

  public static byte[] serialize(Object object) {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Output output = new Output(stream);

    timelineSerializationKryo.get().writeObject(output, object);

    output.close(); // close() also calls flush()
    return stream.toByteArray();
  }

  public static <T> T deserialize(byte[] buffer, Class<T> clazz) {
    return timelineSerializationKryo.get()
        .readObject(new Input(new ByteArrayInputStream(buffer)), clazz);
  }
}
