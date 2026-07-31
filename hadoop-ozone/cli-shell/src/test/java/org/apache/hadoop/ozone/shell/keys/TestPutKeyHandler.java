/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.shell.keys;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hadoop.hdds.client.OzoneStoragePolicy;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneDataStreamOutput;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.shell.OzoneAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for PutKeyHandler.
 */
public class TestPutKeyHandler {
  private static final String KEY_URI = "o3://om/volume/bucket/key";

  @TempDir
  private Path tempDir;

  private OzoneAddress address;
  private PutKeyHandler command;
  private OzoneClient client;
  private OzoneBucket bucket;

  @BeforeEach
  void setUp() throws IOException {
    address = new OzoneAddress(KEY_URI);
    OzoneConfiguration conf = new OzoneConfiguration();
    command = new PutKeyHandler() {
      @Override
      protected OzoneConfiguration getConf() {
        return conf;
      }
    };

    client = mock(OzoneClient.class);
    ObjectStore objectStore = mock(ObjectStore.class);
    OzoneVolume volume = mock(OzoneVolume.class);
    bucket = mock(OzoneBucket.class);
    when(client.getObjectStore()).thenReturn(objectStore);
    when(objectStore.getVolume("volume")).thenReturn(volume);
    when(volume.getBucket("bucket")).thenReturn(bucket);
    when(bucket.getMetadata()).thenReturn(ImmutableMap.of());
  }

  @Test
  void passesStoragePolicyToRegularKeyCreate() throws IOException {
    Path data = createDataFile();
    OzoneOutputStream output = mock(OzoneOutputStream.class);
    when(bucket.createKey(eq("key"), eq(3L), any(), anyMap(), anyMap(),
        eq(OzoneStoragePolicy.HOT))).thenReturn(output);
    parse(data, "--storagepolicy", "hot");

    command.execute(client, address);
    verify(bucket).createKey(eq("key"), eq(3L), any(), anyMap(), anyMap(),
        eq(OzoneStoragePolicy.HOT));
  }

  @Test
  void passesStoragePolicyToStreamingKeyCreate() throws IOException {
    Path data = createDataFile();
    OzoneDataStreamOutput output = mock(OzoneDataStreamOutput.class);
    when(bucket.createStreamKey(eq("key"), eq(3L), any(), anyMap(),
        anyMap(), eq(OzoneStoragePolicy.COLD))).thenReturn(output);
    parse(data, "--stream", "--storagepolicy", "COLD");

    command.execute(client, address);
    verify(bucket).createStreamKey(eq("key"), eq(3L), any(), anyMap(),
        anyMap(), eq(OzoneStoragePolicy.COLD));
  }

  @Test
  void passesStoragePolicyToKeyRewrite() throws IOException {
    Path data = createDataFile();
    OzoneOutputStream output = mock(OzoneOutputStream.class);
    when(bucket.rewriteKey(eq("key"), eq(3L), eq(7L), any(), anyMap(),
        eq(OzoneStoragePolicy.WARM))).thenReturn(output);
    parse(data, "--expectedGeneration", "7", "--storagepolicy", "WARM");

    command.execute(client, address);
    verify(bucket).rewriteKey(eq("key"), eq(3L), eq(7L), any(), anyMap(),
        eq(OzoneStoragePolicy.WARM));
  }

  @Test
  void rejectsInvalidStoragePolicy() throws IOException {
    Path data = createDataFile();
    parse(data, "--storagepolicy", "invalid");

    assertThrows(IllegalArgumentException.class,
        () -> command.execute(client, address));
  }

  private Path createDataFile() throws IOException {
    return Files.write(tempDir.resolve("data"), new byte[]{1, 2, 3});
  }

  private void parse(Path data, String... options) {
    String[] args = new String[options.length + 2];
    args[0] = KEY_URI;
    args[1] = data.toString();
    System.arraycopy(options, 0, args, 2, options.length);
    new CommandLine(command).parseArgs(args);
  }
}
