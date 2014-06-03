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

package org.sakaiproject.lrs.expapi;

import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import net.oauth.OAuthAccessor;
import net.oauth.OAuthConsumer;
import net.oauth.OAuthException;
import net.oauth.OAuthMessage;
import net.oauth.OAuthServiceProvider;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.validator.routines.UrlValidator;
import org.azeckoski.reflectutils.transcoders.JSONTranscoder;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.entitybroker.util.http.HttpClientWrapper;
import org.sakaiproject.entitybroker.util.http.HttpRESTUtils;
import org.sakaiproject.entitybroker.util.http.HttpRESTUtils.Method;
import org.sakaiproject.entitybroker.util.http.HttpResponse;
import org.sakaiproject.event.api.LearningResourceStoreProvider;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Actor;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Context;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Object;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Result;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Statement;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Verb;

/**
 * tincanapi LRS provider Understands how to handle LRS statements to a TincanAPI service.
 * 
 * @author Charles Hasegawa (chasegawa @ Unicon.net)
 * @author Aaron Zeckoski (azeckoski @ unicon.net) (azeckoski @ vt.edu)
 */
public class TincanapiLearningResourceStoreProvider implements LearningResourceStoreProvider {
    private static Log logger = LogFactory.getLog(TincanapiLearningResourceStoreProvider.class);

    private static final String apiVersion = "1.0.0";
    private static final HashMap<String, String> EMPTY_PARAMS = new HashMap<String, String>(0);
    private static final FastDateFormat FORMATTER = DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT;
    private static final String TEST_CONN_MESSAGE = "{\"actor\": {\"mbox\": \"mailto:no-reply@sakaiTCAPI.com\",\"name\": \"Sakai startup connection test\",\"objectType\": \"Agent\"},\"verb\": {\"id\": \"http://adlnet.gov/expapi/verbs/interacted\",\"display\": {\"en-US\": \"interacted\"}},\"object\": {\"id\": \"http://www.example.com/tincan/activities/OyeZsHFR\",\"objectType\": \"Activity\",\"definition\": {\"name\": {\"en-US\": \"Example Activity\"}}}}";
    private static final boolean GUARANTEE_SSL = true;
    private static final String ACTOR = "actor";
    private static final String CONTEXT = "context";
    private static final String OBJECT = "object";
    private static final String RESULT = "result";
    private static final String STORED = "stored";
    private static final String TIMESTAMP = "timestamp";
    private static final String VERB = "verb";

    // config variables
    private String basicAuthString;
    private ServerConfigurationService configurationService;
    private String consumerKey;
    private String consumerSecret;
    private String id = "tincanapi";
    private String realm;
    private int timeout = 0;
    private String url;

    // calculated variables
    private String configPrefix;
    private HashMap<String, String> headers;
    private HttpClientWrapper httpClientWrapper;
    private JSONTranscoder jsonTranscoder;
    private OAuthServiceProvider oAuthServiceProvider;
    private OAuthAccessor oAuthAccessor;

    /**
     * @param configurationService sakai SCS
     */
    public TincanapiLearningResourceStoreProvider(ServerConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * Build up the pieces needed for getting the two-step OAuth header values
     */
    private void configForOAuth() {
        oAuthServiceProvider = new OAuthServiceProvider("notUsed", "notUsed", "notUsed");
        oAuthAccessor = new OAuthAccessor(new OAuthConsumer("notUsed", consumerKey, consumerSecret, oAuthServiceProvider));
    }

    /**
     * @return create a JSON string from the various elements of the supplied statement
     */
    private String convertLRS_StatementToJSON(LRS_Statement statement) {
        HashMap<String, Object> statementMap = new HashMap<String, Object>();
        // Actor, verb and object are required
        try {
            statementMap.put(ACTOR, getActorMap(statement.getActor()));
            statementMap.put(VERB, getVerbMap(statement.getVerb()));
            statementMap.put(OBJECT, getObjectMap(statement.getObject()));
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to handle supplied LRS_Statement", e);
            }
            throw new IllegalArgumentException("Unable to handle supplied LRS_Statement.\nUnable to process Actor, Verb, or Object");
        }

        if (null != statement.getContext()) {
            statementMap.put(CONTEXT, getContextMap(statement.getContext()));
        }
        if (null != statement.getResult()) {
            statementMap.put(RESULT, getResultMap(statement.getResult()));
        }
        if (null != statement.getStored()) {
            statementMap.put(STORED, FORMATTER.format(statement.getStored()));
        }
        if (null != statement.getTimestamp()) {
            statementMap.put(TIMESTAMP, FORMATTER.format(statement.getTimestamp()));
        }
        return jsonTranscoder.encode(statementMap, null, null);
    }

    /**
     * Shutdown the provider
     */
    public void destroy() {
        if (httpClientWrapper != null) {
            try {
                httpClientWrapper.shutdown();
            } catch (Exception e) {
                e.printStackTrace();
            }
            httpClientWrapper = null;
        }
        jsonTranscoder = null;
        logger = null;
    }

    /**
     * @return A map of the actor values
     */
    private Map<String, String> getActorMap(LRS_Actor actor) {
        HashMap<String, String> result = new NonNullValueHashMap<String, String>();
        result.put("mbox", actor.getMbox());
        result.put("name", actor.getName());
        result.put("objectType", actor.getObjectType());
        return result;
    }

    /**
     * @return a map of the values from the context
     */
    private Map<String, Object> getContextMap(LRS_Context context) {
        HashMap<String, Object> result = new NonNullValueHashMap<String, Object>();
        result.put("contextActivities", context.getActivitiesMap());
        // Instructor optional
        if (null != context.getInstructor()) {
            result.put("instructor", getActorMap(context.getInstructor()));
        }
        // Revision optional
        if (null != context.getRevision()) {
            result.put("revision", context.getRevision());
        }
        return result;
    }

    /**
     * @see org.sakaiproject.event.api.LearningResourceStoreProvider#getID()
     */
    public String getID() {
        return id;
    }

    /**
     * @return a map of the values from the LRS object
     */
    private Map<String, Object> getObjectMap(LRS_Object lrsObject) {
        HashMap<String, Object> result = new NonNullValueHashMap<String, Object>();
        result.put("id", lrsObject.getId());
        result.put("objectType", "Activity");
        HashMap<String, Object> definition = new NonNullValueHashMap<String, Object>();
        definition.put("name", lrsObject.getActivityName());
        definition.put("type", lrsObject.getActivityType());
        definition.put("description", lrsObject.getDescription());
        result.put("definition", definition);
        return result;
    }


    /**
     * @return a map of the values from the LRS result
     */
    private Map<String, Object> getResultMap(LRS_Result result) {
        HashMap<String, Object> resultMap = new NonNullValueHashMap<String, Object>();
        resultMap.put("completion", result.getCompletion());

        // Duration has to be formatted to https://en.wikipedia.org/wiki/ISO_8601#Durations
        if (result.getDuration() > 0) {
            resultMap.put("duration", "PT" + result.getDuration() + "S");
        }

        // Grade should only be set if there is no numeric value set
        if (StringUtils.isEmpty(result.getGrade())) {
            HashMap<String, Object> score = new NonNullValueHashMap<String, Object>();
            score.put("max", result.getMax());
            score.put("min", result.getMin());
            score.put("raw", result.getRaw());
            score.put("scaled", result.getScaled());
            resultMap.put("score", score);
        } else {
            HashMap<String, Object> extensions = new NonNullValueHashMap<String, Object>();
            HashMap<String, Object> classification = new NonNullValueHashMap<String, Object>();
            classification.put("objectType", "activity");
            classification.put("id", "http://sakaiproject.org/xapi/activities/" + result.getGrade());
            HashMap<String, Object> definition = new NonNullValueHashMap<String, Object>();
            definition.put("type", "http://sakaiproject.org/xapi/activitytypes/grade_classification");
            HashMap<String, Object> name = new NonNullValueHashMap<String, Object>();
            definition.put("name", name);
            name.put("en-US", result.getGrade());
            classification.put("definition", definition);
            extensions.put("http://sakaiproject.org/xapi/extensions/result/classification", classification);
            resultMap.put("extensions", extensions);
        }

        resultMap.put("success", result.getSuccess());
        resultMap.put("response", result.getResponse());
        return resultMap;
    }

    /**
     * @return a map of values from the LRS verb
     */
    private Map<String, Object> getVerbMap(LRS_Verb verb) {
        HashMap<String, Object> result = new NonNullValueHashMap<String, Object>();
        result.put("id", verb.getId());
        result.put("display", verb.getDisplay());
        return result;
    }


    /**
     * Parse the data from the LRS statement and handle sending the request to the configured receiver. If there is an issue in
     * sending (due to endpoint being misconfigured or unavailable), we simply log the statement.
     * 
     * @see org.sakaiproject.event.api.LearningResourceStoreProvider#handleStatement(org.sakaiproject.event.api.LearningResourceStoreService.LRS_Statement)
     */
    public void handleStatement(LRS_Statement statement) {
        String data = null;
        if (statement.isPopulated()) {
            data = convertLRS_StatementToJSON(statement);
            if (logger.isDebugEnabled())
                logger.debug("LRS using populated statement: " + data);
        } else if (statement.getRawMap() != null && !statement.getRawMap().isEmpty()) {
            data = jsonTranscoder.encode(statement.getRawMap(), null, null);
            if (logger.isDebugEnabled())
                logger.debug("LRS using raw Map statement: " + data);
        } else {
            data = statement.getRawJSON();
            if (logger.isDebugEnabled())
                logger.debug("LRS using raw JSON statement: " + data);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("LRS Attempting to handle statement: " + statement);
        }
        try {
            HttpResponse response = postData(data);
            if (response.getResponseCode() >= 200 && response.getResponseCode() < 300) {
                if (logger.isDebugEnabled())
                    logger.debug(id + " LRS provider successfully sent statement: " + statement);
            } else {
                logger.warn(id + " LRS provider failed (" + response.getResponseCode() + " " + response.getResponseMessage()
                        + ") sending statement (" + statement + ") to ("+url+"), response: " + response.getResponseBody());
            }
        } catch (Exception e) {
            logger.error(id + " LRS provider exception (" + e + "): Statement was not sent.\n Statement data: " + data);
        }
    }

    /**
     * Initialize the state of this provider, reading in the configuration.
     * 
     * @throws URISyntaxException
     * @throws IOException
     * @throws OAuthException
     */
    public void init() throws OAuthException, IOException, URISyntaxException {
        readConfig();
        // Don't allow api version to be configured... we only should be reporting it
        logger.info(id + " LRS provider (version " + apiVersion + ") configured: " + url);
        
        headers = new HashMap<String, String>(3);
        headers.put("Content-Type", "application/json");
        headers.put("X-Experience-API-Version", apiVersion);
        if (isNotEmpty(basicAuthString)) {
            // Note that the SCORM example javascript 64 encoder does use + and / in their output, so we do NOT use the URL safe
            // version of this method here to match their logic
            headers.put("Authorization", "Basic " + Base64.encodeBase64String((basicAuthString).getBytes()));
        } else {
            configForOAuth();
        }
        jsonTranscoder = new JSONTranscoder(true, true, false);
        httpClientWrapper = HttpRESTUtils.makeReusableHttpClient(true, timeout, null);
        HttpResponse response;
        try {
            response = postData(TEST_CONN_MESSAGE);
            if (response.getResponseCode() == 200) {
                logger.info(id + " LRS provider configured and ready");
            } else {
                logger.error(id + " LRS provider not configured properly OR LRS is offline - test message failed!");
                // Future? notify system admin via email
            }
        } catch (Exception e) {
            logger.error(id + " LRS provider failure while trying to contact the LRS! Initialization test failed: " + e.getMessage());
        }
        logger.info(id + " LRS provider INIT complete");
    }

    private HttpResponse postData(String data) throws OAuthException, IOException, URISyntaxException {
        if (isEmpty(basicAuthString)) {
            OAuthMessage message = oAuthAccessor.newRequestMessage(OAuthMessage.POST, url, null);
            message.sign(oAuthAccessor);
            String authHeader = message.getAuthorizationHeader(realm);
            headers.put("Authorization", authHeader);
        }
        HttpResponse response = HttpRESTUtils.fireRequest(httpClientWrapper, url, Method.POST, EMPTY_PARAMS, headers, data,
                GUARANTEE_SSL);
        return response;
    }

    /**
     * Read the setup from the configuration. All non-empty values will overwrite any values that may have been set due to DI
     * Values are prefixed with "lrs.[id]." so that multiple versions of this provider can be instantiated based on the id.
     */
    private void readConfig() {
        if (isEmpty(id)) {
            throw new IllegalStateException("Invalid " + id + " for LRS provider, cannot start");
        }
        configPrefix = "lrs." + id + ".";
        
        String value = configurationService.getConfig(configPrefix + "url", "");
        url = isNotEmpty(value) ? value : url;
        // ensure the URL is valid and formatted the same way (add "/" if not on the end for example)
        UrlValidator urlValidator = new UrlValidator(UrlValidator.ALLOW_LOCAL_URLS);
        if (!urlValidator.isValid(url)) {
            throw new IllegalStateException("Invalid " + id + " LRS provider url (" + url + "), correct the " + configPrefix
                    + "url config value");
        } else {
            if (!url.endsWith("/")) {
                url = url + "/";
            }
        }

        value = configurationService.getConfig(configPrefix + "request.timeout", "");
        try {
            timeout = isNotEmpty(value) ? Integer.parseInt(value) : timeout; // allow setter to override
        } catch (NumberFormatException e) {
            if (logger.isDebugEnabled()) {
                timeout = 0;
                logger.debug(configPrefix + "request.timeout must be an integer value - using default setting", e);
            }
        }

        // basic auth
        value = configurationService.getConfig(configPrefix + "basicAuthUserPass", "");
        basicAuthString = isNotEmpty(value) ? value : basicAuthString;

        // oauth fields
        value = configurationService.getConfig(configPrefix + "consumer.key", "");
        consumerKey = isNotEmpty(value) ? value : consumerKey;
        value = configurationService.getConfig(configPrefix + "consumer.secret", "");
        consumerSecret = isNotEmpty(value) ? value : consumerSecret;
        value = configurationService.getConfig(configPrefix + "realm", "");
        realm = isNotEmpty(value) ? value : realm;

        if (isEmpty(basicAuthString) && (isEmpty(consumerKey) || isEmpty(consumerSecret) || isEmpty(realm))) {
            throw new IllegalStateException(
                    "No authentication configured properly for LRS provider, service cannot start. Please check the configuration");
        }
    }

    // Setters

    public void setBasicAuthString(String authString) {
        this.basicAuthString = authString;
    }

    public void setConsumerKey(String consumerKey) {
        this.consumerKey = consumerKey;
    }

    public void setConsumerSecret(String consumerSecret) {
        this.consumerSecret = consumerSecret;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public void setServerConfigurationService(ServerConfigurationService serverConfigurationService) {
        this.configurationService = serverConfigurationService;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setUrl(String url) {
        this.url = url;
    }


    static class NonNullValueHashMap<K, V> extends HashMap<K, V> {
        private static final long serialVersionUID = 1L;

        /**
         * If the key or value is null, we don't add it to the map. This allows us to "quietly" ignore storing nulls
         * 
         * @see java.util.HashMap#put(java.lang.Object, java.lang.Object)
         */
        @Override
        public V put(K key, V value) {
            return (null == key || null == value) ? null : super.put(key, value);
        }
    }
}
