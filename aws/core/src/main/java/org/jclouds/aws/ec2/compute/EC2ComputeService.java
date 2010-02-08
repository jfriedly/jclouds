/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.aws.ec2.compute;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.aws.ec2.options.RunInstancesOptions.Builder.withKeyName;
import static org.jclouds.concurrent.ConcurrentUtils.awaitCompletion;
import static org.jclouds.concurrent.ConcurrentUtils.makeListenable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.jclouds.Constants;
import org.jclouds.aws.domain.Region;
import org.jclouds.aws.ec2.EC2Client;
import org.jclouds.aws.ec2.compute.domain.EC2Size;
import org.jclouds.aws.ec2.compute.domain.KeyPairCredentials;
import org.jclouds.aws.ec2.compute.domain.PortsRegionTag;
import org.jclouds.aws.ec2.compute.domain.RegionTag;
import org.jclouds.aws.ec2.compute.functions.CreateKeyPairIfNeeded;
import org.jclouds.aws.ec2.compute.functions.CreateSecurityGroupIfNeeded;
import org.jclouds.aws.ec2.compute.functions.RunningInstanceToNodeMetadata;
import org.jclouds.aws.ec2.domain.AvailabilityZone;
import org.jclouds.aws.ec2.domain.InstanceState;
import org.jclouds.aws.ec2.domain.Reservation;
import org.jclouds.aws.ec2.domain.RunningInstance;
import org.jclouds.aws.ec2.options.RunInstancesOptions;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.ComputeType;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.NodeState;
import org.jclouds.compute.domain.Size;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.internal.BaseComputeService;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.compute.util.ComputeUtils;
import org.jclouds.domain.Location;
import org.jclouds.domain.LocationScope;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.internal.ImmutableSet;

/**
 * @author Adrian Cole
 */
@Singleton
public class EC2ComputeService extends BaseComputeService {

   private static Function<RunningInstance, String> instanceToId = new Function<RunningInstance, String>() {
      @Override
      public String apply(RunningInstance from) {
         return from.getId();
      }
   };
   protected final EC2Client ec2Client;
   protected final Map<RegionTag, KeyPairCredentials> credentialsMap;
   protected final Map<PortsRegionTag, String> securityGroupMap;
   protected final CreateKeyPairIfNeeded createKeyPairIfNeeded;
   protected final CreateSecurityGroupIfNeeded createSecurityGroupIfNeeded;
   protected final Predicate<RunningInstance> instanceStateRunning;
   protected final Predicate<RunningInstance> instanceStateTerminated;
   protected final RunningInstanceToNodeMetadata runningInstanceToNodeMetadata;

   @Inject
   EC2ComputeService(Provider<Map<String, ? extends Image>> images,
            Provider<Map<String, ? extends Size>> sizes,
            Provider<Map<String, ? extends Location>> locations,
            Provider<TemplateBuilder> templateBuilderProvider, String nodeNamingConvention,
            ComputeUtils utils, @Named(Constants.PROPERTY_USER_THREADS) ExecutorService executor,
            EC2Client ec2Client, Map<RegionTag, KeyPairCredentials> credentialsMap,
            Map<PortsRegionTag, String> securityGroupMap,
            CreateKeyPairIfNeeded createKeyPairIfNeeded,
            CreateSecurityGroupIfNeeded createSecurityGroupIfNeeded,
            @Named("RUNNING") Predicate<RunningInstance> instanceStateRunning,
            @Named("TERMINATED") Predicate<RunningInstance> instanceStateTerminated,
            RunningInstanceToNodeMetadata runningInstanceToNodeMetadata) {
      super(images, sizes, locations, templateBuilderProvider, nodeNamingConvention, utils,
               executor);
      this.ec2Client = ec2Client;
      this.credentialsMap = credentialsMap;
      this.securityGroupMap = securityGroupMap;
      this.createKeyPairIfNeeded = createKeyPairIfNeeded;
      this.createSecurityGroupIfNeeded = createSecurityGroupIfNeeded;
      this.instanceStateRunning = instanceStateRunning;
      this.instanceStateTerminated = instanceStateTerminated;
      this.runningInstanceToNodeMetadata = runningInstanceToNodeMetadata;
   }

   @Override
   public Map<String, NodeMetadata> runNodesWithTag(String tag, int count, final Template template) {
      checkArgument(tag.indexOf('-') == -1, "tag cannot contain hyphens");
      checkArgument(template.getSize() instanceof EC2Size,
               "unexpected image type. should be EC2Size, was: " + template.getSize().getClass());
      EC2Size ec2Size = EC2Size.class.cast(template.getSize());

      // parse the availability zone of the request
      AvailabilityZone zone = template.getLocation().getScope() == LocationScope.ZONE ? AvailabilityZone
               .fromValue(template.getLocation().getId())
               : null;

      // if the location has a parent, it must be an availability zone.
      Region region = zone == null ? Region.fromValue(template.getLocation().getId()) : Region
               .fromValue(template.getLocation().getParent());

      // get or create incidental resources
      // TODO race condition. we were using MapMaker, but it doesn't seem to refresh properly when
      // another thread
      // deletes a key
      RegionTag regionTag = new RegionTag(region, tag);
      if (!credentialsMap.containsKey(regionTag)) {
         credentialsMap.put(regionTag, createKeyPairIfNeeded.apply(regionTag));
      }
      TemplateOptions options = template.getOptions();
      PortsRegionTag portsRegionTag = new PortsRegionTag(region, tag, options.getInboundPorts());
      if (!securityGroupMap.containsKey(portsRegionTag)) {
         securityGroupMap.put(portsRegionTag, createSecurityGroupIfNeeded.apply(portsRegionTag));
      }

      logger
               .debug(
                        ">> running %d instance region(%s) zone(%s) ami(%s) type(%s) keyPair(%s) securityGroup(%s)",
                        count, region, zone, template.getImage().getId(),
                        ec2Size.getInstanceType(), tag, tag);
      RunInstancesOptions instanceOptions = withKeyName(tag)// key
               .asType(ec2Size.getInstanceType())// instance size
               .withSecurityGroup(tag)// group I created above
               .withAdditionalInfo(tag);
      final Set<NodeMetadata> nodes = Sets.newHashSet();
      int nodesToStart = count;
      while (nodesToStart > 0) {
         Reservation reservation = ec2Client.getInstanceServices().runInstancesInRegion(region,
                  zone, template.getImage().getId(), 1, nodesToStart, instanceOptions);
         Iterable<String> ids = Iterables.transform(reservation, instanceToId);

         String idsString = Joiner.on(',').join(ids);
         logger.debug("<< started instances(%s)", idsString);
         Iterables.all(reservation, instanceStateRunning);
         logger.debug("<< running instances(%s)", idsString);
         Map<NodeMetadata, ListenableFuture<Void>> responses = Maps.newHashMap();
         for (final NodeMetadata node : Iterables.transform(Iterables.concat(ec2Client
                  .getInstanceServices().describeInstancesInRegion(region,
                           Iterables.toArray(ids, String.class))), runningInstanceToNodeMetadata)) {
            responses.put(node, makeListenable(executor.submit(new Callable<Void>() {
               @Override
               public Void call() throws Exception {
                  try {
                     utils.runOptionsOnNode(node, template.getOptions());
                     logger.debug("<< options applied instance(%s)", node.getId());
                     nodes.add(node);
                  } catch (Exception e) {
                     logger.error(e, "<< error applying instance(%s) [%s] destroying ", node
                              .getId(), e.getMessage());
                     destroyNode(node);
                  }
                  return null;
               }

            }), executor));
         }
         nodesToStart = awaitCompletion(responses, executor, null, logger, "nodes").size();
      }
      return Maps.uniqueIndex(nodes, METADATA_TO_ID);
   }

   @Override
   public NodeMetadata getNodeMetadata(ComputeMetadata node) {
      checkArgument(node.getType() == ComputeType.NODE, "this is only valid for nodes, not "
               + node.getType());
      checkNotNull(node.getId(), "node.id");
      Region region = getRegionFromNodeOrDefault(node);
      RunningInstance runningInstance = Iterables.getOnlyElement(getAllRunningInstancesInRegion(
               region, node.getId()));
      return runningInstanceToNodeMetadata.apply(runningInstance);
   }

   private Iterable<RunningInstance> getAllRunningInstancesInRegion(Region region, String id) {
      return Iterables
               .concat(ec2Client.getInstanceServices().describeInstancesInRegion(region, id));
   }

   protected Iterable<NodeMetadata> doGetNodes() {
      Set<NodeMetadata> nodes = Sets.newHashSet();
      for (Region region : ImmutableSet.of(Region.US_EAST_1, Region.US_WEST_1, Region.EU_WEST_1)) {
         Iterables.addAll(nodes, Iterables.transform(Iterables.concat(ec2Client
                  .getInstanceServices().describeInstancesInRegion(region)),
                  runningInstanceToNodeMetadata));
      }
      return nodes;
   }

   @Override
   protected boolean doDestroyNode(ComputeMetadata metadata) {
      NodeMetadata node = metadata instanceof NodeMetadata ? NodeMetadata.class.cast(metadata)
               : getNodeMetadata(metadata);
      String tag = checkNotNull(node.getTag(), "node.tag");

      Region region = getRegionFromNodeOrDefault(node);

      RunningInstance instance = getInstance(node, region);
      if (instance.getInstanceState() != InstanceState.TERMINATED) {
         logger.debug(">> terminating instance(%s)", node.getId());
         boolean success = false;
         while (!success) {
            ec2Client.getInstanceServices().terminateInstancesInRegion(region, node.getId());
            success = instanceStateTerminated.apply(getInstance(node, region));
         }
         logger.debug("<< terminated instance(%s) success(%s)", node.getId(), success);
      }
      if (Iterables.all(doGetNodes(tag).values(), new Predicate<NodeMetadata>() {
         @Override
         public boolean apply(NodeMetadata input) {
            return input.getState() == NodeState.TERMINATED;
         }
      })) {
         deleteKeyPair(region, tag);
         deleteSecurityGroup(region, tag);
      }
      return true;
   }

   private RunningInstance getInstance(NodeMetadata node, Region region) {
      return Iterables.getOnlyElement(getAllRunningInstancesInRegion(region, node.getId()));
   }

   private void deleteSecurityGroup(Region region, String tag) {
      if (ec2Client.getSecurityGroupServices().describeSecurityGroupsInRegion(region, tag).size() > 0) {
         logger.debug(">> deleting securityGroup(%s)", tag);
         ec2Client.getSecurityGroupServices().deleteSecurityGroupInRegion(region, tag);
         securityGroupMap.remove(new PortsRegionTag(region, tag, null)); // TODO: test this clear
         // happens
         logger.debug("<< deleted securityGroup(%s)", tag);
      }
   }

   private void deleteKeyPair(Region region, String tag) {
      if (ec2Client.getKeyPairServices().describeKeyPairsInRegion(region, tag).size() > 0) {
         logger.debug(">> deleting keyPair(%s)", tag);
         ec2Client.getKeyPairServices().deleteKeyPairInRegion(region, tag);
         credentialsMap.remove(new RegionTag(region, tag)); // TODO: test this clear happens
         logger.debug("<< deleted keyPair(%s)", tag);
      }
   }

   private Region getRegionFromNodeOrDefault(ComputeMetadata node) {
      Location location = getLocations().get(node.getLocationId());
      Region region = location.getScope() == LocationScope.REGION ? Region.fromValue(location
               .getId()) : Region.fromValue(location.getParent());
      return region;
   }

   @Override
   protected NodeMetadata startNode(String tag, String name, Template template) {
      throw new UnsupportedOperationException();
   }

}