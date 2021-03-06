/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.clouddriver.tasks.providers.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.netflix.spinnaker.orca.clouddriver.OortService;
import com.netflix.spinnaker.orca.clouddriver.tasks.image.ImageTagger;
import com.netflix.spinnaker.orca.clouddriver.utils.CloudProviderAware;
import com.netflix.spinnaker.orca.pipeline.model.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.lang.String.format;

@Component
public class AmazonImageTagger implements ImageTagger, CloudProviderAware {
  private static final Logger log = LoggerFactory.getLogger(AmazonImageTagger.class);
  private static final String ALLOW_LAUNCH_OPERATION = "allowLaunchDescription";
  private static final Set<String> BUILT_IN_TAGS = new HashSet<>(
    Arrays.asList("appversion", "base_ami_version", "build_host", "creation_time", "creator")
  );

  @Autowired
  OortService oortService;

  @Autowired
  ObjectMapper objectMapper;

  @Value("${default.bake.account:default}")
  String defaultBakeAccount;

  @Override
  public ImageTagger.OperationContext getOperationContext(Stage stage) {
    StageData stageData = (StageData) stage.mapTo(StageData.class);

    Collection<MatchedImage> matchedImages = findImages(stageData.imageNames, stage);
    if (stageData.regions == null || stageData.regions.isEmpty()) {
      stageData.regions = matchedImages.stream()
        .flatMap(matchedImage -> matchedImage.amis.keySet().stream())
        .collect(Collectors.toSet());
    }

    stageData.imageNames = matchedImages.stream()
      .map(matchedImage -> matchedImage.imageName)
      .collect(Collectors.toSet());

    // Built-in tags are not updatable
    Map<String, String> tags = stageData.tags.entrySet().stream()
      .filter(entry -> !BUILT_IN_TAGS.contains(entry.getKey()))
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    List<Image> targetImages = new ArrayList<>();
    Map<String, Object> originalTags = new HashMap<>();
    List<Map<String, Map>> operations = new ArrayList<>();

    for (MatchedImage matchedImage : matchedImages) {
      Image targetImage = new Image(
        matchedImage.imageName,
        defaultBakeAccount,
        stageData.regions,
        tags
      );
      targetImages.add(targetImage);

      log.info(format("Tagging '%s' with '%s' (executionId: %s)", targetImage.imageName, targetImage.tags, stage.getExecution().getId()));

      // Update the tags on the image in the `defaultBakeAccount`
      operations.add(
        ImmutableMap.<String, Map>builder()
          .put(OPERATION, ImmutableMap.builder()
            .put("amiName", targetImage.imageName)
            .put("tags", targetImage.tags)
            .put("regions", targetImage.regions)
            .put("credentials", targetImage.account)
            .build()
          ).build()
      );

      // Re-share the image in all other accounts (will result in tags being updated)
      matchedImage.accounts.stream()
        .filter(account -> !account.equalsIgnoreCase(defaultBakeAccount))
        .forEach(account -> {
          stageData.regions.forEach(region ->
            operations.add(
              ImmutableMap.<String, Map>builder()
                .put(ALLOW_LAUNCH_OPERATION, ImmutableMap.builder()
                  .put("account", account)
                  .put("credentials", defaultBakeAccount)
                  .put("region", region)
                  .put("amiName", targetImage.imageName)
                  .build()
                ).build()
            )
          );
        });


      originalTags.put(matchedImage.imageName, matchedImage.tagsByImageId);
    }

    Map<String, Object> extraOutput = objectMapper.convertValue(stageData, Map.class);
    extraOutput.put("targets", targetImages);
    extraOutput.put("originalTags", originalTags);
    return new ImageTagger.OperationContext(operations, extraOutput);
  }

  /**
   * Return true iff the tags on the current machine image match the desired.
   */
  @Override
  public boolean areImagesTagged(Collection<Image> targetImages, Stage stage) {
    Collection<MatchedImage> matchedImages = findImages(
      targetImages.stream().map(targetImage -> targetImage.imageName).collect(Collectors.toSet()),
      stage
    );

    AtomicBoolean isUpserted = new AtomicBoolean(true);
    for (Image targetImage : targetImages) {
      targetImage.regions.forEach(region -> {
          MatchedImage matchedImage = matchedImages.stream()
            .filter(m -> m.imageName.equals(targetImage.imageName))
            .findFirst()
            .orElse(null);

          if (matchedImage == null) {
            isUpserted.set(false);
            return;
          }

          List<String> imagesForRegion = matchedImage.amis.get(region);
          imagesForRegion.forEach(image -> {
            Map<String, String> allImageTags = matchedImage.tagsByImageId.getOrDefault(image, new HashMap<>());
            targetImage.tags.entrySet().forEach(entry -> {
              // assert tag equality
              isUpserted.set(isUpserted.get() && entry.getValue().equals(allImageTags.get(entry.getKey())));
            });
          });
        }
      );
    }

    return isUpserted.get();
  }

  @Override
  public String getCloudProvider() {
    return "aws";
  }

  private Collection<MatchedImage> findImages(Collection<String> imageNames, Stage stage) {
    if (imageNames == null || imageNames.isEmpty()) {
      imageNames = new HashSet<>();

      // attempt to find upstream images in the event that one was not explicitly provided
      Collection<String> upstreamImageIds = AmazonImageTaggerSupport.upstreamImageIds(stage);
      if (upstreamImageIds.isEmpty()) {
        throw new IllegalStateException("Unable to determine source image(s)");
      }

      for (String upstreamImageId : upstreamImageIds) {
        // attempt to lookup the equivalent image name (given the upstream amiId/imageId)
        List<Map> allMatchedImages = oortService.findImage(getCloudProvider(), upstreamImageId, null, null, null);
        if (allMatchedImages.isEmpty()) {
          throw new ImageNotFound(format("No image found (imageId: %s)", upstreamImageId), true);
        }

        String upstreamImageName = (String) allMatchedImages.get(0).get("imageName");
        imageNames.add(upstreamImageName);

        log.info(format("Found upstream image '%s' (executionId: %s)", upstreamImageName, stage.getExecution().getId()));
      }
    }

    return imageNames.stream()
      .map(targetImageName -> {
        List<Map> allMatchedImages = oortService.findImage(getCloudProvider(), targetImageName, null, null, null);
        Map matchedImage = allMatchedImages.stream()
          .filter(image -> image.get("imageName").equals(targetImageName))
          .findFirst()
          .orElseThrow(() -> new ImageNotFound(format("No image found (imageName: %s)", targetImageName), false));
        return objectMapper.convertValue(matchedImage, MatchedImage.class);
      })
      .collect(Collectors.toList());
  }

  static class StageData {
    public Set<String> imageNames;
    public Set<String> regions = new HashSet<>();
    public Map<String, String> tags = new HashMap<>();
  }

  private static class MatchedImage {
    public String imageName;
    public Collection<String> accounts;
    public Map<String, List<String>> amis;
    public Map<String, Map<String, String>> tagsByImageId;
  }
}
