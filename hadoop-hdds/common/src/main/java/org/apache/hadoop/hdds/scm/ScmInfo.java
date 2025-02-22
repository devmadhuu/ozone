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

package org.apache.hadoop.hdds.scm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ScmInfo wraps the result returned from SCM#getScmInfo which
 * contains clusterId and the SCM Id.
 */
public final class ScmInfo {
  private final String clusterId;
  private final String scmId;
  private final List<String> peerRoles;
  private final boolean scmRatisEnabled;

  /**
   * Builder for ScmInfo.
   */
  public static class Builder {
    private String clusterId;
    private String scmId;
    private final List<String> peerRoles;
    private boolean scmRatisEnabled;

    public Builder() {
      peerRoles = new ArrayList<>();
    }

    /**
     * sets the cluster id.
     * @param cid clusterId to be set
     * @return Builder for ScmInfo
     */
    public Builder setClusterId(String cid) {
      this.clusterId = cid;
      return this;
    }

    /**
     * sets the scmId.
     * @param id scmId
     * @return Builder for scmInfo
     */
    public Builder setScmId(String id) {
      this.scmId = id;
      return this;
    }

    /**
     * Set peer address in Scm HA.
     * @param roles ratis peer address in the format of [ip|hostname]:port
     * @return  Builder for scmInfo
     */
    public Builder setRatisPeerRoles(List<String> roles) {
      peerRoles.addAll(roles);
      return this;
    }

    /**
     * Set whether SCM enables Ratis.
     *
     * @param ratisEnabled If it is true, it means that the Ratis mode is turned on.
     * If it is false, it means that the Ratis mode is not turned on.
     * @return Builder for scmInfo
     */
    public Builder setScmRatisEnabled(boolean ratisEnabled) {
      scmRatisEnabled = ratisEnabled;
      return this;
    }

    public ScmInfo build() {
      return new ScmInfo(clusterId, scmId, peerRoles, scmRatisEnabled);
    }
  }

  private ScmInfo(String clusterId, String scmId, List<String> peerRoles, boolean ratisEnabled) {
    this.clusterId = clusterId;
    this.scmId = scmId;
    this.peerRoles = Collections.unmodifiableList(peerRoles);
    this.scmRatisEnabled = ratisEnabled;
  }

  /**
   * Gets the clusterId from the Version file.
   * @return ClusterId
   */
  public String getClusterId() {
    return clusterId;
  }

  /**
   * Gets the SCM Id from the Version file.
   * @return SCM Id
   */
  public String getScmId() {
    return scmId;
  }

  /**
   * Gets the list of peer roles (currently address) in Scm HA.
   * @return List of peer address
   */
  public List<String> getRatisPeerRoles() {
    return peerRoles;
  }

  public boolean getScmRatisEnabled() {
    return scmRatisEnabled;
  }
}
