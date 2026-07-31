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

package org.apache.hadoop.ozone.protocol.commands;

import com.google.protobuf.ByteString;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdds.HddsIdFactory;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.StorageTypeUtils;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ReconstructECContainersCommandProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ReconstructECContainersCommandProto.Builder;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.SCMCommandProto.Type;

/**
 * SCM command to request reconstruction of EC containers.
 */
public class ReconstructECContainersCommand
    extends SCMCommand<ReconstructECContainersCommandProto> {
  private final long containerID;
  private final List<DatanodeDetailsAndReplicaIndex> sources;
  private final List<ECReconstructionTarget> reconstructionTargets;
  private final ByteString missingContainerIndexes;
  private final ECReplicationConfig ecReplicationConfig;

  public ReconstructECContainersCommand(long containerID,
      List<DatanodeDetailsAndReplicaIndex> sources,
      List<?> targetDatanodes, ByteString missingContainerIndexes,
      ECReplicationConfig ecReplicationConfig) {
    this(containerID, sources, targetDatanodes, missingContainerIndexes,
        ecReplicationConfig, HddsIdFactory.getLongId());
  }

  public ReconstructECContainersCommand(long containerID,
      List<DatanodeDetailsAndReplicaIndex> sourceDatanodes,
      List<?> targetDatanodes, ByteString missingContainerIndexes,
      ECReplicationConfig ecReplicationConfig, long id) {
    super(id);
    this.containerID = containerID;
    this.sources = sourceDatanodes;
    this.reconstructionTargets = toReconstructionTargets(targetDatanodes);
    this.missingContainerIndexes = missingContainerIndexes;
    this.ecReplicationConfig = ecReplicationConfig;
    if (reconstructionTargets.size() != missingContainerIndexes.size()) {
      throw new IllegalArgumentException("Number of target datanodes and " +
          "container indexes should be same");
    }
  }

  @Override
  public Type getType() {
    return Type.reconstructECContainersCommand;
  }

  @Override
  public ReconstructECContainersCommandProto getProto() {
    Builder builder =
        ReconstructECContainersCommandProto.newBuilder().setCmdId(getId())
            .setContainerID(containerID);
    for (DatanodeDetailsAndReplicaIndex dd : sources) {
      builder.addSources(dd.toProto());
    }
    for (ECReconstructionTarget target : reconstructionTargets) {
      builder.addTargets(target.getDatanodeDetails().getProtoBufMessage());
      builder.addReconstructionTargets(target.toProto());
    }
    builder.setMissingContainerIndexes(missingContainerIndexes);
    builder.setEcReplicationConfig(ecReplicationConfig.toProto());
    return builder.build();
  }

  public static ReconstructECContainersCommand getFromProtobuf(
      ReconstructECContainersCommandProto protoMessage) {
    Objects.requireNonNull(protoMessage, "protoMessage == null");

    List<DatanodeDetailsAndReplicaIndex> srcDatanodeDetails =
        protoMessage.getSourcesList().stream()
            .map(a -> DatanodeDetailsAndReplicaIndex.fromProto(a))
            .collect(Collectors.toList());
    List<ECReconstructionTarget> targetDatanodeDetails;
    if (protoMessage.getReconstructionTargetsCount() > 0) {
      targetDatanodeDetails = protoMessage.getReconstructionTargetsList()
          .stream().map(ECReconstructionTarget::fromProto)
          .collect(Collectors.toList());
    } else {
      targetDatanodeDetails = protoMessage.getTargetsList().stream()
          .map(DatanodeDetails::getFromProtoBuf)
          .map(dn -> new ECReconstructionTarget(dn, null))
          .collect(Collectors.toList());
    }

    return new ReconstructECContainersCommand(protoMessage.getContainerID(),
        srcDatanodeDetails, targetDatanodeDetails,
        protoMessage.getMissingContainerIndexes(),
        new ECReplicationConfig(protoMessage.getEcReplicationConfig()),
        protoMessage.getCmdId());
  }

  public long getContainerID() {
    return containerID;
  }

  public List<DatanodeDetailsAndReplicaIndex> getSources() {
    return sources;
  }

  public List<DatanodeDetails> getTargetDatanodes() {
    return reconstructionTargets.stream()
        .map(ECReconstructionTarget::getDatanodeDetails)
        .collect(Collectors.toList());
  }

  public List<ECReconstructionTarget> getReconstructionTargets() {
    return reconstructionTargets;
  }

  public ByteString getMissingContainerIndexes() {
    return missingContainerIndexes;
  }

  public ECReplicationConfig getEcReplicationConfig() {
    return ecReplicationConfig;
  }

  private static List<ECReconstructionTarget> toReconstructionTargets(
      List<?> targets) {
    List<ECReconstructionTarget> result = new ArrayList<>(targets.size());
    for (Object target : targets) {
      if (target instanceof ECReconstructionTarget) {
        result.add((ECReconstructionTarget) target);
      } else if (target instanceof DatanodeDetails) {
        result.add(new ECReconstructionTarget((DatanodeDetails) target, null));
      } else {
        throw new IllegalArgumentException("Unsupported reconstruction target: "
            + target);
      }
    }
    return result;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getType())
        .append(": cmdID: ").append(getId())
        .append(", encodedToken: \"").append(getEncodedToken()).append('"')
        .append(", term: ").append(getTerm())
        .append(", deadlineMsSinceEpoch: ").append(getDeadline())
        .append(", containerID: ").append(containerID)
        .append(", replicationConfig: ").append(ecReplicationConfig)
        .append(", sources: [").append(getSources().stream()
            .map(a -> a.dnDetails
                + " replicaIndex: " + a.getReplicaIndex())
            .collect(Collectors.joining(", "))).append(']')
        .append(", targets: ").append(getReconstructionTargets())
        .append(", missingIndexes: ").append(
            Arrays.toString(missingContainerIndexes.toByteArray()));
    return sb.toString();
  }

  /**
   * To store the datanode details with replica index.
   */
  public static class DatanodeDetailsAndReplicaIndex {
    private DatanodeDetails dnDetails;
    private int replicaIndex;

    public DatanodeDetailsAndReplicaIndex(DatanodeDetails dnDetails,
        int replicaIndex) {
      this.dnDetails = dnDetails;
      this.replicaIndex = replicaIndex;
    }

    public DatanodeDetails getDnDetails() {
      return dnDetails;
    }

    public int getReplicaIndex() {
      return replicaIndex;
    }

    public StorageContainerDatanodeProtocolProtos
        .DatanodeDetailsAndReplicaIndexProto toProto() {
      return StorageContainerDatanodeProtocolProtos
          .DatanodeDetailsAndReplicaIndexProto.newBuilder()
          .setDatanodeDetails(dnDetails.getProtoBufMessage())
          .setReplicaIndex(replicaIndex).build();
    }

    public static DatanodeDetailsAndReplicaIndex fromProto(
        StorageContainerDatanodeProtocolProtos
            .DatanodeDetailsAndReplicaIndexProto proto) {
      return new DatanodeDetailsAndReplicaIndex(
          DatanodeDetails.getFromProtoBuf(proto.getDatanodeDetails()),
          proto.getReplicaIndex());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DatanodeDetailsAndReplicaIndex that = (DatanodeDetailsAndReplicaIndex) o;
      return replicaIndex == that.replicaIndex && Objects
          .equals(dnDetails, that.dnDetails);
    }

    @Override
    public int hashCode() {
      return Objects.hash(dnDetails, replicaIndex);
    }
  }

  /**
   * Target datanode and storage type for an EC reconstruction.
   */
  public static class ECReconstructionTarget {
    private final DatanodeDetails datanodeDetails;
    private final StorageType storageType;

    public ECReconstructionTarget(DatanodeDetails datanodeDetails,
        @Nullable StorageType storageType) {
      this.datanodeDetails = Objects.requireNonNull(datanodeDetails);
      this.storageType = storageType;
    }

    public DatanodeDetails getDatanodeDetails() {
      return datanodeDetails;
    }

    @Nullable
    public StorageType getStorageType() {
      return storageType;
    }

    public StorageContainerDatanodeProtocolProtos.ECReconstructionTargetProto
        toProto() {
      StorageContainerDatanodeProtocolProtos.ECReconstructionTargetProto.Builder
          builder = StorageContainerDatanodeProtocolProtos
          .ECReconstructionTargetProto.newBuilder()
          .setDatanodeDetails(datanodeDetails.getProtoBufMessage());
      if (storageType != null) {
        builder.setStorageType(
            StorageTypeUtils.getStorageTypeProto(storageType));
      }
      return builder.build();
    }

    public static ECReconstructionTarget fromProto(
        StorageContainerDatanodeProtocolProtos.ECReconstructionTargetProto
            proto) {
      StorageType type = proto.hasStorageType()
          ? StorageTypeUtils.getFromProtobuf(proto.getStorageType()) : null;
      return new ECReconstructionTarget(
          DatanodeDetails.getFromProtoBuf(proto.getDatanodeDetails()), type);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ECReconstructionTarget that = (ECReconstructionTarget) o;
      return datanodeDetails.equals(that.datanodeDetails)
          && storageType == that.storageType;
    }

    @Override
    public int hashCode() {
      return Objects.hash(datanodeDetails, storageType);
    }

    @Override
    public String toString() {
      return datanodeDetails + " storageType: " + storageType;
    }
  }
}
