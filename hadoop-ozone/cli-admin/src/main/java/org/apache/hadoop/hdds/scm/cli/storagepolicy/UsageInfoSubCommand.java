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

package org.apache.hadoop.hdds.scm.cli.storagepolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.client.StorageTypeUtils;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.DatanodeDetailsProto;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeOperationalState;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.NodeState;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerLocationProtocolProtos.DatanodeStorageTypeUsageInfoProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerLocationProtocolProtos.ListStorageTypeUsageInfoRequestProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerLocationProtocolProtos.StorageTypeUsageInfoProto;
import org.apache.hadoop.hdds.scm.cli.ScmSubcommand;
import org.apache.hadoop.hdds.scm.client.ScmClient;
import org.apache.hadoop.util.StringUtils;
import picocli.CommandLine;

/**
 * Handler for {@code ozone admin storagepolicy usageinfo}.
 */
@CommandLine.Command(
    name = "usageinfo",
    description = "List usage information in the StoragePolicy dimension",
    mixinStandardHelpOptions = true,
    versionProvider = HddsVersionProvider.class)
public class UsageInfoSubCommand extends ScmSubcommand {

  // index constants for the per-type long[] arrays
  private static final int CAP  = 0;
  private static final int USED = 1;
  private static final int REM  = 2;
  private static final int COM  = 3;
  private static final int FSS  = 4;

  @CommandLine.Option(
      names = {"-wd", "--with-datanode"},
      description = "Print detailed per-datanode storage-type usage",
      defaultValue = "false")
  private boolean printDatanodeInfo;

  @CommandLine.Option(
      names = {"-os", "--operational-state"},
      description = "Show datanodes in a specific operational state "
          + "(IN_SERVICE, DECOMMISSIONING, DECOMMISSIONED, "
          + "ENTERING_MAINTENANCE, IN_MAINTENANCE), "
          + "or ALL to show all operational states",
      defaultValue = "IN_SERVICE")
  private String nodeOperationalStateStr;

  @CommandLine.Option(
      names = {"-ns", "--node-state"},
      description = "Show datanodes in a specific health state "
          + "(HEALTHY, STALE, DEAD), or ALL to show all health states",
      defaultValue = "HEALTHY")
  private String nodeStateStr;

  @Override
  public void execute(ScmClient scmClient) throws IOException {
    NodeOperationalState nodeOpState = null;
    NodeState nodeState = null;
    if (!nodeOperationalStateStr.equalsIgnoreCase("ALL")) {
      nodeOpState = NodeOperationalState.valueOf(
          nodeOperationalStateStr.toUpperCase());
    }
    if (!nodeStateStr.equalsIgnoreCase("ALL")) {
      nodeState = NodeState.valueOf(nodeStateStr.toUpperCase());
    }

    ListStorageTypeUsageInfoRequestProto.Builder reqBuilder =
        ListStorageTypeUsageInfoRequestProto.newBuilder();
    if (nodeOpState != null) {
      reqBuilder.setOpState(nodeOpState);
    }
    if (nodeState != null) {
      reqBuilder.setState(nodeState);
    }

    List<DatanodeStorageTypeUsageInfoProto> usageInfos =
        scmClient.listStorageTypeUsageInfo(reqBuilder.build());
    printStorageTypeSummaryInfo(usageInfos);
    if (printDatanodeInfo) {
      printStorageTypeDatanodeInfo(usageInfos);
    }
  }

  private void printStorageTypeDatanodeInfo(
      List<DatanodeStorageTypeUsageInfoProto> dnInfos) {
    Map<StorageType,
        List<Pair<DatanodeDetailsProto, StorageTypeUsageInfoProto>>>
        dnMap = new HashMap<>();
    for (StorageType t : StorageType.values()) {
      dnMap.put(t, new ArrayList<>());
    }

    for (DatanodeStorageTypeUsageInfoProto dn : dnInfos) {
      for (StorageTypeUsageInfoProto st : dn.getStorageTypeUsageInfoList()) {
        try {
          StorageType t = StorageTypeUtils.getFromProtobuf(st.getStorageType());
          dnMap.get(t).add(Pair.of(dn.getDatanodeDetails(), st));
        } catch (IllegalArgumentException ignored) {
          // no mapping for this proto value — skip
        }
      }
    }

    System.out.println("Datanode StorageType Usage List");
    for (StorageType storageType : StorageType.values()) {
      List<Pair<DatanodeDetailsProto, StorageTypeUsageInfoProto>> entries =
          dnMap.get(storageType);
      if (entries != null && !entries.isEmpty()) {
        System.out.printf("%nStorageType %s: %n%n", storageType);
        for (Pair<DatanodeDetailsProto, StorageTypeUsageInfoProto> pair
            : entries) {
          DatanodeDetailsProto dd = pair.getLeft();
          StorageTypeUsageInfoProto st = pair.getRight();
          long cap = st.getCapacity();
          long used = st.getUsed();
          long rem = st.getRemaining();
          long com = st.getCommitted();
          long fss = st.getFreeSpaceToSpare();
          System.out.printf("  %-23s: %s (%s, %s, %s) %n",
              "Datanode", dd.getUuid(),
              dd.getHostName(), dd.getIpAddress(),
              dd.getNetworkLocation());
          System.out.printf("  %-23s: %s (%s) %n",
              storageType + " Capacity", cap + " B", StringUtils.byteDesc(cap));
          System.out.printf("  %-23s: %s (%s) %n",
              storageType + " Ozone Used", used + " B",
              StringUtils.byteDesc(used));
          System.out.printf("  %-23s: %s (%s) %n",
              storageType + " Remaining", rem + " B",
              StringUtils.byteDesc(rem));
          System.out.printf("  %-23s: %s (%s) %n",
              storageType + " Committed", com + " B",
              StringUtils.byteDesc(com));
          System.out.printf("  %-23s: %s (%s) %n",
              storageType + " FreeSpaceToSpare", fss + " B",
              StringUtils.byteDesc(fss));
          System.out.println();
        }
      }
    }
  }

  private void printStorageTypeSummaryInfo(
      List<DatanodeStorageTypeUsageInfoProto> dnInfos) {
    // totals[type] = long[]{cap, used, remaining, committed, freeSpaceToSpare}
    Map<StorageType, long[]> totals = new EnumMap<>(StorageType.class);
    Map<StorageType, Integer> dnCount = new EnumMap<>(StorageType.class);

    for (DatanodeStorageTypeUsageInfoProto dn : dnInfos) {
      for (StorageTypeUsageInfoProto st : dn.getStorageTypeUsageInfoList()) {
        try {
          StorageType t = StorageTypeUtils.getFromProtobuf(st.getStorageType());
          totals.computeIfAbsent(t, k -> new long[5]);
          totals.get(t)[CAP]  += st.getCapacity();
          totals.get(t)[USED] += st.getUsed();
          totals.get(t)[REM]  += st.getRemaining();
          totals.get(t)[COM]  += st.getCommitted();
          totals.get(t)[FSS]  += st.getFreeSpaceToSpare();
          dnCount.merge(t, 1, Integer::sum);
        } catch (IllegalArgumentException ignored) {
          // no mapping — skip
        }
      }
    }

    System.out.println("Cluster StorageType Usage Summary");
    for (StorageType storageType : StorageType.values()) {
      int count = dnCount.getOrDefault(storageType, 0);
      if (count > 0) {
        long[] v = totals.get(storageType);
        System.out.printf("  %-23s: %s %n",
            storageType + " Datanode Count", count);
        System.out.printf("  %-23s: %s (%s) %n",
            storageType + " Capacity",
            v[CAP] + "B", StringUtils.byteDesc(v[CAP]));
        System.out.printf("  %-23s: %s (%s) %n",
            storageType + " Ozone Used",
            v[USED] + "B", StringUtils.byteDesc(v[USED]));
        System.out.printf("  %-23s: %s (%s) %n",
            storageType + " Remaining",
            v[REM] + "B", StringUtils.byteDesc(v[REM]));
        System.out.printf("  %-23s: %s (%s) %n",
            storageType + " Committed",
            v[COM] + "B", StringUtils.byteDesc(v[COM]));
        System.out.printf("  %-23s: %s (%s) %n",
            storageType + " FreeSpaceToSpare",
            v[FSS] + "B", StringUtils.byteDesc(v[FSS]));
        System.out.println();
      }
    }
  }
}
