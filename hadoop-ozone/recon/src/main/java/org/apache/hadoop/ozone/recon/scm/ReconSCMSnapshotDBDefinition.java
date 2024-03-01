/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.hadoop.ozone.recon.scm;

import org.apache.hadoop.hdds.scm.metadata.SCMDBDefinition;
import org.apache.hadoop.ozone.recon.ReconServerConfigKeys;

import static org.apache.hadoop.ozone.recon.ReconConstants.RECON_SCM_SNAPSHOT_DB;

/**
 * Recon SCM db snapshot file for ozone.
 */
public class ReconSCMSnapshotDBDefinition extends SCMDBDefinition {

  public static final String RECON_SCM_DB_NAME = RECON_SCM_SNAPSHOT_DB;

  public ReconSCMSnapshotDBDefinition() {
    super();
  }

  @Override
  public String getName() {
    return RECON_SCM_DB_NAME;
  }

  @Override
  public String getLocationConfigKey() {
    return ReconServerConfigKeys.OZONE_RECON_SCM_DB_DIR;
  }
}
