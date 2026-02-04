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

package org.apache.hadoop.ozone.admin.recon;

import java.util.concurrent.Callable;

import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.admin.ConfigAnalyzer;
import picocli.CommandLine;

/**
 * Handler for Recon configuration analyzer command.
 */
@CommandLine.Command(
    name = "config-analyzer",
    description = "Analyze Recon configuration for violations of best practices",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class ConfigAnalyzerSubcommand implements Callable<Void> {

  @CommandLine.ParentCommand
  private ReconAdmin parent;

  @CommandLine.Option(names = {"--json"},
      defaultValue = "false",
      description = "Format output as JSON")
  private boolean json;

  @CommandLine.Option(names = {"--table"},
      defaultValue = "false",
      description = "Format output as Table")
  private boolean table;

  @CommandLine.Option(names = {"--rules"},
      description = "Path to custom Recon rules JSON file")
  private String rulesPath;

  @CommandLine.Option(names = {"--recon-host"},
      description = "Recon HTTP address (host:port). If not specified, " +
          "will try to detect from configuration")
  private String reconHost;

  @Override
  public Void call() throws Exception {
    OzoneConfiguration conf = parent.getParent().getOzoneConf();
    
    // Determine Recon HTTP address
    String reconHttpAddress = reconHost;
    if (reconHttpAddress == null || reconHttpAddress.isEmpty()) {
      // Try to get from config, or use default
      // Recon HTTP address config key: ozone.recon.http-bind-host
      String host = conf.get("ozone.recon.http-bind-host", "0.0.0.0");
      int port = conf.getInt("ozone.recon.http-address.port", 9888);
      reconHttpAddress = host + ":" + port;
    }

    ConfigAnalyzer analyzer = new ConfigAnalyzer(
        "RECON",
        conf,
        rulesPath,
        "config-analyzer-rules/recon-rules.json",
        reconHttpAddress
    );

    analyzer.analyze(json, table);
    return null;
  }
}
