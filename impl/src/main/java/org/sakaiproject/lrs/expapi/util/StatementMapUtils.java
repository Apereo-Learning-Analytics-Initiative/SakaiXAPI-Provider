/**
 * Copyright 2013 Unicon (R) Licensed under the
 * Educational Community License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.sakaiproject.lrs.expapi.util;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Actor;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Context;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Object;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Result;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Verb;
import org.sakaiproject.lrs.expapi.model.LRSKeys;
import org.sakaiproject.lrs.expapi.model.NonNullValueHashMap;

/**
 * Utilities to process LRS Statement objects
 * 
 * @author Robert Long (rlong @ unicon.net)
 */
public class StatementMapUtils implements LRSKeys {

    /**
     * @return a map of the actor values
     */
    public static Map<String, Object> getActorMap(LRS_Actor actor) {
        HashMap<String, Object> actorMap = new NonNullValueHashMap<>();

        actorMap.put(LRSActorKey.name.toString(), actor.getName());
        actorMap.put(LRSActorKey.objectType.toString(), actor.getObjectType());

        ServerConfigurationService serverConfigurationService = (ServerConfigurationService) ComponentManager.get(ServerConfigurationService.class);
        String identifier = serverConfigurationService.getString(INVERSE_FUNCTIONAL_IDENTIFIER_PROPERTY, LRSIdentifierKey.account.toString());

        if (StringUtils.equalsIgnoreCase(identifier, LRSIdentifierKey.mbox.toString())) {
            actorMap.put(LRSIdentifierKey.mbox.toString(), actor.getMbox());
        } else if (StringUtils.equalsIgnoreCase(identifier, LRSIdentifierKey.mbox_sha1sum.toString())) {
            actorMap.put(LRSIdentifierKey.mbox_sha1sum.toString(), DigestUtils.sha1Hex(actor.getMbox()));
        } else if (StringUtils.equalsIgnoreCase(identifier, LRSIdentifierKey.openid.toString())) {
            actorMap.put(LRSActorKey.openid.toString(), actor.getOpenid());
        } else {
            // default to "account"
            HashMap<String, String> accountMap = new NonNullValueHashMap<String, String>();
            String name = actor.getAccount().getName();
            if (StringUtils.isBlank(name)) {
                name = "unknown";
            }
            accountMap.put(LRSActorKey.name.toString(), name);

            String homePage = actor.getAccount().getHomePage();
            if (StringUtils.isBlank(homePage)) {
                homePage = serverConfigurationService.getServerUrl();
            }
            accountMap.put(LRSActorKey.homePage.toString(), homePage);
            actorMap.put(LRSActorKey.account.toString(), accountMap);
        }

        return actorMap;
    }

    /**
     * @return a map of the values from the context
     */
    public static Map<String, Object> getContextMap(LRS_Context context) {
        HashMap<String, Object> contextMap = new NonNullValueHashMap<>();
        contextMap.put(LRSContextKey.contextActivities.toString(), context.getActivitiesMap());

        // Instructor optional
        if (null != context.getInstructor()) {
            contextMap.put(LRSContextKey.instructor.toString(), getActorMap(context.getInstructor()));
        }

        // Revision optional
        if (null != context.getRevision()) {
            contextMap.put(LRSContextKey.revision.toString(), context.getRevision());
        }

        return contextMap;
    }

    /**
     * @return a map of the values from the LRS object
     */
    public static Map<String, Object> getObjectMap(LRS_Object lrsObject) {
        HashMap<String, Object> definitionMap = new NonNullValueHashMap<>();
        definitionMap.put(LRSDefinitionKey.name.toString(), lrsObject.getActivityName());
        definitionMap.put(LRSDefinitionKey.type.toString(), lrsObject.getActivityType());
        definitionMap.put(LRSDefinitionKey.description.toString(), lrsObject.getDescription());

        HashMap<String, Object> objectMap = new NonNullValueHashMap<>();
        objectMap.put(LRSObjectKey.id.toString(), lrsObject.getId());
        objectMap.put(LRSObjectKey.objectType.toString(), "Activity");
        objectMap.put(LRSObjectKey.definition.toString(), definitionMap);

        return objectMap;
    }

    /**
     * @return a map of the values from the LRS result
     */
    public static Map<String, Object> getResultMap(LRS_Result result) {
        HashMap<String, Object> resultMap = new NonNullValueHashMap<>();
        resultMap.put(LRSResultKey.completion.toString(), result.getCompletion());

        // Duration has to be formatted to https://en.wikipedia.org/wiki/ISO_8601#Durations
        if (result.getDuration() > 0) {
            resultMap.put(LRSResultKey.duration.toString(), "PT" + result.getDuration() + "S");
        }

        // Grade should only be set if there is no numeric value set
        if (StringUtils.isEmpty(result.getGrade())) {
            HashMap<String, Object> scoreMap = new NonNullValueHashMap<>();
            scoreMap.put(LRSScoreKey.max.toString(), result.getMax());
            scoreMap.put(LRSScoreKey.min.toString(), result.getMin());
            scoreMap.put(LRSScoreKey.raw.toString(), result.getRaw());
            scoreMap.put(LRSScoreKey.scaled.toString(), result.getScaled());

            resultMap.put(LRSResultKey.score.toString(), scoreMap);
        } else {
            HashMap<String, Object> name = new NonNullValueHashMap<>();
            name.put("en-US", result.getGrade());

            HashMap<String, Object> definition = new NonNullValueHashMap<>();
            definition.put(LRSDefinitionKey.type.toString(), "http://sakaiproject.org/xapi/activitytypes/grade_classification");
            definition.put(LRSDefinitionKey.name.toString(), name);

            HashMap<String, Object> classification = new NonNullValueHashMap<>();
            classification.put(LRSObjectKey.objectType.toString(), "activity");
            classification.put(LRSObjectKey.id.toString(), "http://sakaiproject.org/xapi/activities/" + result.getGrade());
            classification.put(LRSObjectKey.definition.toString(), definition);

            HashMap<String, Object> extensions = new NonNullValueHashMap<>();
            extensions.put("http://sakaiproject.org/xapi/extensions/result/classification", classification);

            resultMap.put(LRSResultKey.extensions.toString(), extensions);
        }

        resultMap.put(LRSResultKey.success.toString(), result.getSuccess());
        resultMap.put(LRSResultKey.response.toString(), result.getResponse());

        return resultMap;
    }

    /**
     * @return a map of values from the LRS verb
     */
    public static Map<String, Object> getVerbMap(LRS_Verb verb) {
        HashMap<String, Object> result = new NonNullValueHashMap<>();
        result.put(LRSVerbKey.id.toString(), verb.getId());
        result.put(LRSVerbKey.display.toString(), verb.getDisplay());

        return result;
    }

}
