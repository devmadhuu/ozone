/*
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

package org.apache.hadoop.ozone.recon.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.hadoop.hdds.client.DefaultReplicationConfig;
import org.apache.hadoop.hdds.protocol.StorageType;
import org.apache.hadoop.ozone.om.helpers.BucketEncryptionKeyInfo;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;

/**
 * Encapsulates the low level bucket info.
 */
public class BucketObjectDBInfo extends ObjectDBInfo {
  @JsonProperty("volumeName")
  private String volumeName;

  @JsonProperty("storageType")
  private StorageType storageType;

  @JsonProperty("versioning")
  private boolean isVersioningEnabled;

  @JsonProperty("usedBytes")
  private String usedBytes;

  @JsonProperty("encryptionInfo")
  private BucketEncryptionKeyInfo bekInfo;

  @JsonProperty("replicationConfigInfo")
  private DefaultReplicationConfig defaultReplicationConfig;

  @JsonProperty("sourceVolume")
  private String sourceVolume;

  @JsonProperty("sourceBucket")
  private String sourceBucket;

  @JsonProperty("bucketLayout")
  private BucketLayout bucketLayout;

  @JsonProperty("owner")
  private String owner;

  public static BucketObjectDBInfo.Builder newBuilder() {
    return new BucketObjectDBInfo.Builder();
  }

  public BucketObjectDBInfo() {

  }

  public BucketObjectDBInfo(Builder b) {
    this.setMetadata(b.getOmBucketInfo().getMetadata());
    this.setVolumeName(b.getOmBucketInfo().getVolumeName());
    this.setName(b.getOmBucketInfo().getBucketName());
    this.setQuotaInBytes(b.getOmBucketInfo().getQuotaInBytes());
    this.setQuotaInNamespace(
        b.getOmBucketInfo().getQuotaInNamespace());
    this.setUsedNamespace(b.getOmBucketInfo().getUsedNamespace());
    this.setCreationTime(b.getOmBucketInfo().getCreationTime());
    this.setModificationTime(
        b.getOmBucketInfo().getModificationTime());
    this.setAcls(b.getOmBucketInfo().getAcls());
    this.setSourceBucket(b.getOmBucketInfo().getSourceBucket());
    this.setSourceVolume(b.getOmBucketInfo().getSourceVolume());
    this.setBekInfo(b.getOmBucketInfo().getEncryptionKeyInfo());
    this.setVersioningEnabled(
        b.getOmBucketInfo().getIsVersionEnabled());
    this.setStorageType(b.getOmBucketInfo().getStorageType());
    this.setDefaultReplicationConfig(
        b.getOmBucketInfo().getDefaultReplicationConfig());
    this.setBucketLayout(b.getOmBucketInfo().getBucketLayout());
    this.setOwner(b.getOmBucketInfo().getOwner());
  }

  public String getVolumeName() {
    return volumeName;
  }

  public void setVolumeName(String volumeName) {
    this.volumeName = volumeName;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public void setStorageType(StorageType storageType) {
    this.storageType = storageType;
  }

  public String getUsedBytes() {
    return usedBytes;
  }

  public void setUsedBytes(String usedBytes) {
    this.usedBytes = usedBytes;
  }

  public BucketEncryptionKeyInfo getBekInfo() {
    return bekInfo;
  }

  public void setBekInfo(BucketEncryptionKeyInfo bekInfo) {
    this.bekInfo = bekInfo;
  }

  public DefaultReplicationConfig getDefaultReplicationConfig() {
    return defaultReplicationConfig;
  }

  public void setDefaultReplicationConfig(
      DefaultReplicationConfig defaultReplicationConfig) {
    this.defaultReplicationConfig = defaultReplicationConfig;
  }

  public String getSourceVolume() {
    return sourceVolume;
  }

  public void setSourceVolume(String sourceVolume) {
    this.sourceVolume = sourceVolume;
  }

  public String getSourceBucket() {
    return sourceBucket;
  }

  public void setSourceBucket(String sourceBucket) {
    this.sourceBucket = sourceBucket;
  }

  public boolean isVersioningEnabled() {
    return isVersioningEnabled;
  }

  public void setVersioningEnabled(boolean versioningEnabled) {
    isVersioningEnabled = versioningEnabled;
  }

  public BucketLayout getBucketLayout() {
    return bucketLayout;
  }

  public void setBucketLayout(BucketLayout bucketLayout) {
    this.bucketLayout = bucketLayout;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  /**
   * Builder for BucketObjectDBInfo.
   */
  @SuppressWarnings("checkstyle:hiddenfield")
  public static final class Builder {
    private OmBucketInfo omBucketInfo;
    public Builder() {

    }

    public BucketObjectDBInfo.Builder setOmBucketInfo(
        OmBucketInfo omBucketInfo) {
      this.omBucketInfo = omBucketInfo;
      return this;
    }

    public OmBucketInfo getOmBucketInfo() {
      return omBucketInfo;
    }

    public BucketObjectDBInfo build() {
      if (null == this.omBucketInfo) {
        return new BucketObjectDBInfo();
      }
      return new BucketObjectDBInfo(this);
    }
  }
}
