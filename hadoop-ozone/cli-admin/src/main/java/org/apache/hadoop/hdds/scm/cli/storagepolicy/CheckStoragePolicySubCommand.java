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

package org.apache.hadoop.hdds.scm.cli.storagepolicy;

import static org.apache.hadoop.hdds.client.StorageTierUtil.getStorageTypeForUniformStorageTier;

import java.io.IOException;
import java.util.List;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.client.StoragePolicy;
import org.apache.hadoop.hdds.client.StorageTier;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.scm.cli.ScmSubcommand;
import org.apache.hadoop.hdds.scm.client.ScmClient;
import org.apache.hadoop.hdds.scm.container.ContainerReplicaInfo;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientException;
import org.apache.hadoop.ozone.client.OzoneKeyDetails;
import org.apache.hadoop.ozone.client.OzoneKeyLocation;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.shell.OzoneAddress;
import org.apache.hadoop.ozone.shell.Shell;
import org.apache.hadoop.ozone.shell.keys.KeyUri;
import picocli.CommandLine;

/**
 * Check whether a key satisfies its bucket's StoragePolicy.
 *
 * <p>Usage: {@code ozone admin storagepolicy check <key-uri> [--show-replicas]}
 *
 * <p>Note: full replica-level StorageType checking (replica.getStorageType(),
 * replica.getVolumeStorageType(), replica.getContainerPath()) and key-level
 * StoragePolicy population (OzoneKeyDetails.getStoragePolicy()) depend on later
 * patches (16 and 27 respectively) and are guarded with TODO comments.
 */
@CommandLine.Command(
    name = "check",
    description = "Check Key Storage Policy",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class CheckStoragePolicySubCommand extends ScmSubcommand {

  @CommandLine.ParentCommand
  private StoragePolicyCommands parent;

  @CommandLine.Parameters(index = "0", arity = "1..1",
      description = "URI of the key.\n" + Shell.OZONE_URI_DESCRIPTION,
      converter = KeyUri.class)
  private OzoneAddress keyAddress;

  @CommandLine.Option(
      names = {"--show-replicas"},
      description = "Show detailed container replica information.")
  private boolean showReplicas;

  private boolean keyMatchStoragePolicy = true;

  @Override
  protected void execute(ScmClient scmClient) throws IOException {
    OzoneConfiguration conf = parent.getParent().getOzoneConf();
    try (OzoneClient ozoneClient = keyAddress.createClient(conf)) {
      OzoneVolume volume = ozoneClient.getObjectStore()
          .getVolume(keyAddress.getVolumeName());
      OzoneBucket bucket = volume.getBucket(keyAddress.getBucketName());
      OzoneKeyDetails keyDetails = bucket.getKey(keyAddress.getKeyName());
      System.out.println(buildKeyStoragePolicyInfo(keyDetails));
      if (showReplicas) {
        System.out.println(buildContainerReplicaInfo(keyDetails, scmClient));
      }
    } catch (OzoneClientException e) {
      throw new RuntimeException(e);
    }
  }

  private String buildKeyStoragePolicyInfo(OzoneKeyDetails keyInfo) {
    StringBuilder sb = new StringBuilder();
    // TODO(patch-16): OzoneKeyDetails.getStoragePolicy() is populated by
    //   "Ozone create key support StoragePolicy" patch. Until that patch
    //   lands this always returns null.
    StoragePolicy storagePolicy = keyInfo.getStoragePolicy();
    int requiredNodes = keyInfo.getReplicationConfig().getRequiredNodes();
    sb.append(String.format("Storage Policy for key '%s/%s/%s':\n",
        keyAddress.getVolumeName(), keyAddress.getBucketName(),
        keyAddress.getKeyName()));

    if (storagePolicy == null) {
      sb.append("  Key Unset StoragePolicy\n");
    } else {
      StorageType creationStorageType =
          getStorageTypeForUniformStorageTier(storagePolicy.getCreationTier());
      sb.append(String.format("  %s{Creation Tier: [%s x %d], ",
          storagePolicy, creationStorageType, requiredNodes));
      if (storagePolicy.getCreationFallbackTier().equals(StorageTier.EMPTY)) {
        sb.append(" Fallback Tier: [disable]}");
      } else {
        StorageType fallbackStorageType = getStorageTypeForUniformStorageTier(
            storagePolicy.getCreationFallbackTier());
        sb.append(String.format(
            " Fallback Tier: [%s x %d]}", fallbackStorageType, requiredNodes));
      }
      sb.append(String.format("  Key Match Storage Policy: %s\n",
          keyMatchStoragePolicy ? "YES" : "NO"));
    }
    return sb.toString();
  }

  private String buildContainerReplicaInfo(OzoneKeyDetails keyDetails,
      ScmClient scmClient) {
    StringBuilder sb = new StringBuilder();
    List<OzoneKeyLocation> keyLocations = keyDetails.getOzoneKeyLocations();
    StoragePolicy storagePolicy = keyDetails.getStoragePolicy();
    boolean hasStoragePolicy = storagePolicy != null;

    for (OzoneKeyLocation keyLocation : keyLocations) {
      long containerId = keyLocation.getContainerID();
      sb.append(String.format("Container ID: %d\n", containerId));

      try {
        List<ContainerReplicaInfo> replicas =
            scmClient.getContainerReplicas(containerId);
        for (ContainerReplicaInfo replica : replicas) {
          DatanodeDetails datanode = replica.getDatanodeDetails();
          sb.append(String.format("  Datanode: %s (%s, %s)\n",
              datanode.getUuid(), datanode.getHostName(),
              datanode.getNetworkLocation()));
          sb.append(String.format("  Replica state: %s\n",
              replica.getState()));
          // TODO(patch-27): replica.getStorageType(), getVolumeStorageType(),
          //   and getContainerPath() are added by the
          //   "Container info command support display StorageType" patch.
          //   Once that patch lands, add StorageType-matching logic here and
          //   update keyMatchStoragePolicy accordingly.
          sb.append("\n");
        }
      } catch (IOException e) {
        throw new RuntimeException(String.format(
            "Error: Unable to retrieve container details for %d",
            containerId), e);
      }

      String matchStatus = hasStoragePolicy
          ? (keyMatchStoragePolicy ? "YES" : "NO")
          : "Key unset StoragePolicy";
      sb.append(String.format(
          "  Container Match Storage Policy: %s\n", matchStatus));
    }
    return sb.toString();
  }
}
