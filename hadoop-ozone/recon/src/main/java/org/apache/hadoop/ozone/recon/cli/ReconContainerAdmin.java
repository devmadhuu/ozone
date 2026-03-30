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

package org.apache.hadoop.ozone.recon.cli;

import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import picocli.CommandLine.Command;

/**
 * Admin command group for Recon container operations.
 *
 * <p>Bootstrapped by {@link ReconContainerMain}, which is registered as
 * {@code ozone recon-container} in the {@code ozone} shell script using
 * {@code OZONE_RUN_ARTIFACT_NAME=ozone-recon}, ensuring the full Recon
 * RocksDB library is on the classpath.
 *
 * <p>Example usage:
 * <pre>
 *   ozone recon-container export-keys \
 *       --container-state QUASI_CLOSED \
 *       --recon-db /var/data/recon/recon-container-key.db \
 *       --output-dir /tmp/export \
 *       --threads 16 \
 *       --compress
 * </pre>
 */
@Command(
    name = "recon-container",
    description = "Recon-specific container operations",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class,
    subcommands = {
        ExportKeysSubcommand.class
    })
public class ReconContainerAdmin {
}
