/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.recon.api.handlers;

import org.apache.hadoop.hdds.scm.server.OzoneStorageContainerManager;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.recon.api.types.CountStats;
import org.apache.hadoop.ozone.recon.api.types.KeyObjectDBInfo;
import org.apache.hadoop.ozone.recon.api.types.NamespaceSummaryResponse;
import org.apache.hadoop.ozone.recon.api.types.EntityType;
import org.apache.hadoop.ozone.recon.api.types.ObjectDBInfo;
import org.apache.hadoop.ozone.recon.api.types.ResponseStatus;
import org.apache.hadoop.ozone.recon.api.types.DUResponse;
import org.apache.hadoop.ozone.recon.api.types.QuotaUsageResponse;
import org.apache.hadoop.ozone.recon.api.types.FileSizeDistributionResponse;
import org.apache.hadoop.ozone.recon.api.types.Stats;
import org.apache.hadoop.ozone.recon.recovery.ReconOMMetadataManager;
import org.apache.hadoop.ozone.recon.spi.ReconNamespaceSummaryManager;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Class for handling key entity type.
 */
public class KeyEntityHandler extends EntityHandler {
  public KeyEntityHandler(
      ReconNamespaceSummaryManager reconNamespaceSummaryManager,
      ReconOMMetadataManager omMetadataManager,
      OzoneStorageContainerManager reconSCM,
      BucketHandler bucketHandler, String path) {
    super(reconNamespaceSummaryManager, omMetadataManager,
          reconSCM, bucketHandler, path);
  }

  @Override
  public NamespaceSummaryResponse getSummaryResponse()
          throws IOException {
    CountStats countStats = new CountStats(
        -1, -1,
        -1, 0);
    return NamespaceSummaryResponse.newBuilder()
        .setEntityType(EntityType.KEY)
        .setCountStats(countStats)
        .setObjectDBInfo(getKeyDbObjectInfo(getNames()))
        .setStatus(ResponseStatus.OK)
        .build();
  }

  private ObjectDBInfo getKeyDbObjectInfo(String[] names)
      throws IOException {
    OmKeyInfo omKeyInfo = getBucketHandler().getKeyInfo(names);
    if (null == omKeyInfo) {
      return new KeyObjectDBInfo();
    }
    return new KeyObjectDBInfo(omKeyInfo);
  }

  @Override
  public DUResponse getDuResponse(
      boolean listFile, boolean withReplica, boolean sort, boolean recursive, Stats stats)
          throws IOException {
    DUResponse duResponse = new DUResponse();
    duResponse.setPath(getNormalizedPath());
    OmKeyInfo keyInfo = getBucketHandler().getKeyInfo(getNames());
    duResponse.setKeySize(keyInfo.getDataSize());
    duResponse.setSize(keyInfo.getDataSize());
    if (withReplica) {
      long keySizeWithReplica = keyInfo.getReplicatedSize();
      duResponse.setSizeWithReplica(keySizeWithReplica);
    }
    if (listFile) {
      duResponse.setCount(1);
      DUResponse.DiskUsage diskUsage = new DUResponse.DiskUsage();
      diskUsage.setKey(true);
      diskUsage.setSubpath(getNormalizedPath());
      diskUsage.setSize(keyInfo.getDataSize());
      diskUsage.setSizeWithReplica(duResponse.getSizeWithReplica());
      diskUsage.setReplicationType(keyInfo.getReplicationConfig().getReplicationType().name());
      diskUsage.setCreationTime(keyInfo.getCreationTime());
      diskUsage.setModificationTime(keyInfo.getModificationTime());
      ArrayList<DUResponse.DiskUsage> diskUsages = new ArrayList<>();
      diskUsages.add(diskUsage);
      duResponse.setTotalCount(diskUsages.size());
      duResponse.setDuData(diskUsages);
    }
    return duResponse;
  }

  @Override
  public QuotaUsageResponse getQuotaResponse()
          throws IOException {
    QuotaUsageResponse quotaUsageResponse = new QuotaUsageResponse();
    quotaUsageResponse.setResponseCode(
            ResponseStatus.TYPE_NOT_APPLICABLE);
    return quotaUsageResponse;
  }

  @Override
  public FileSizeDistributionResponse getDistResponse()
          throws IOException {
    FileSizeDistributionResponse distResponse =
            new FileSizeDistributionResponse();
    // key itself doesn't have file size distribution
    distResponse.setStatus(ResponseStatus.TYPE_NOT_APPLICABLE);
    return distResponse;
  }

}
