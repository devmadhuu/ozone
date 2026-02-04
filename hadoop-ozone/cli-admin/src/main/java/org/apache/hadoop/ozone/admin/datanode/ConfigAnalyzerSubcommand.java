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

package org.apache.hadoop.ozone.admin.datanode;

import java.util.concurrent.Callable;

import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.admin.ConfigAnalyzer;
import picocli.CommandLine;

/**
 * Handler for DataNode configuration analyzer command.
 */
@CommandLine.Command(
    name = "config-analyzer",
    description = "Analyze DataNode configuration for violations of best practices",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class ConfigAnalyzerSubcommand implements Callable<Void> {

  @CommandLine.ParentCommand
  private DatanodeAdmin parent;

  @CommandLine.Option(names = {"--json"},
      defaultValue = "false",
      description = "Format output as JSON")
  private boolean json;

  @CommandLine.Option(names = {"--table"},
      defaultValue = "false",
      description = "Format output as Table")
  private boolean table;

  @CommandLine.Option(names = {"--rules"},
      description = "Path to custom DataNode rules JSON file")
  private String rulesPath;

  @CommandLine.Option(names = {"--datanode-host"},
      description = "DataNode HTTP address (host:port). Required to fetch " +
          "runtime configuration from a specific DataNode")
  private String datanodeHost;

  @Override
  public Void call() throws Exception {
    OzoneConfiguration conf = parent.getParent().getOzoneConf();
    
    // Determine DataNode HTTP address
    String datanodeHttpAddress = datanodeHost;
    if (datanodeHttpAddress == null || datanodeHttpAddress.isEmpty()) {
      datanodeHttpAddress = conf.get(HddsConfigKeys.HDDS_DATANODE_HTTP_ADDRESS_KEY);
    }

    ConfigAnalyzer analyzer = new ConfigAnalyzer(
        "DATANODE",
        conf,
        rulesPath,
        "config-analyzer-rules/datanode-rules.json",
        datanodeHttpAddress
    );
    
    analyzer.analyze(json, table);
    return null;
  }
}
