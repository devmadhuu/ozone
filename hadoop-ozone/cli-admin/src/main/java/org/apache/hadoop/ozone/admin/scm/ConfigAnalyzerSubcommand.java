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

package org.apache.hadoop.ozone.admin.scm;

import java.io.IOException;

import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.cli.ScmSubcommand;
import org.apache.hadoop.hdds.scm.client.ScmClient;
import org.apache.hadoop.ozone.admin.ConfigAnalyzer;
import picocli.CommandLine;

/**
 * Handler for SCM configuration analyzer command.
 */
@CommandLine.Command(
    name = "config-analyzer",
    description = "Analyze SCM configuration for violations of best practices",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class ConfigAnalyzerSubcommand extends ScmSubcommand {

  @CommandLine.ParentCommand
  private ScmAdmin parent;

  @CommandLine.Option(names = {"--json"},
      defaultValue = "false",
      description = "Format output as JSON")
  private boolean json;

  @CommandLine.Option(names = {"--table"},
      defaultValue = "false",
      description = "Format output as Table")
  private boolean table;

  @CommandLine.Option(names = {"--rules"},
      description = "Path to custom SCM rules JSON file")
  private String rulesPath;

  @CommandLine.Option(names = {"--scm-host"},
      description = "SCM HTTP address (host:port). If not specified, " +
          "will try to detect from configuration")
  private String scmHost;

  @Override
  public void execute(ScmClient scmClient) throws IOException {
    OzoneConfiguration conf = parent.getParent().getOzoneConf();
    
    // Determine SCM HTTP address
    String scmHttpAddress = scmHost;
    if (scmHttpAddress == null || scmHttpAddress.isEmpty()) {
      scmHttpAddress = conf.get(ScmConfigKeys.OZONE_SCM_HTTP_ADDRESS_KEY);
      if (scmHttpAddress == null) {
        // Construct default from host + port
        scmHttpAddress = ScmConfigKeys.OZONE_SCM_HTTP_BIND_HOST_DEFAULT + ":" +
            ScmConfigKeys.OZONE_SCM_HTTP_BIND_PORT_DEFAULT;
      }
    }

    ConfigAnalyzer analyzer = new ConfigAnalyzer(
        "SCM", 
        conf,
        rulesPath,
        "config-analyzer-rules/scm-rules.json",
        scmHttpAddress
    );
    
    analyzer.analyze(json, table);
  }
}
