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

package org.apache.hadoop.ozone.admin.om;

import java.util.concurrent.Callable;

import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.admin.ConfigAnalyzer;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import picocli.CommandLine;

/**
 * Handler for OM configuration analyzer command.
 */
@CommandLine.Command(
    name = "config-analyzer",
    description = "Analyze OM configuration for violations of best practices",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class ConfigAnalyzerSubcommand implements Callable<Void> {

  @CommandLine.ParentCommand
  private OMAdmin parent;

  @CommandLine.Option(names = {"--json"},
      defaultValue = "false",
      description = "Format output as JSON")
  private boolean json;

  @CommandLine.Option(names = {"--table"},
      defaultValue = "false",
      description = "Format output as Table")
  private boolean table;

  @CommandLine.Option(names = {"--rules"},
      description = "Path to custom OM rules JSON file")
  private String rulesPath;

  @CommandLine.Option(names = {"--om-host"},
      description = "OM HTTP address (host:port). If not specified, " +
          "will try to detect from configuration")
  private String omHost;

  @Override
  public Void call() throws Exception {
    OzoneConfiguration conf = parent.getParent().getOzoneConf();
    
    // Determine OM HTTP address
    String omHttpAddress = omHost;
    if (omHttpAddress == null || omHttpAddress.isEmpty()) {
      omHttpAddress = conf.get(OMConfigKeys.OZONE_OM_HTTP_ADDRESS_KEY);
      if (omHttpAddress == null) {
        // Construct default from host + port
        omHttpAddress = OMConfigKeys.OZONE_OM_HTTP_BIND_HOST_DEFAULT + ":" +
            OMConfigKeys.OZONE_OM_HTTP_BIND_PORT_DEFAULT;
      }
    }

    ConfigAnalyzer analyzer = new ConfigAnalyzer(
        "OM",
        conf,
        rulesPath,
        "config-analyzer-rules/om-rules.json",
        omHttpAddress
    );

    analyzer.analyze(json, table);
    return null;
  }
}
