package org.apache.hadoop.yarn.server.timeline;

import com.google.gson.*;
import org.apache.commons.logging.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.net.*;
import org.apache.hadoop.service.*;
import org.apache.hadoop.yarn.api.records.timeline.*;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateRequestBuilder;
import org.elasticsearch.client.transport.*;
import org.elasticsearch.common.settings.*;
import org.elasticsearch.common.transport.*;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by wankun on 2018/2/6.
 */
public class ElasticSearchTimelineStore extends AbstractService implements TimelineStore {

  private static final Log LOG = LogFactory.getLog(ElasticSearchTimelineStore.class);

  public static final String TIMELINE_SERVICE_ES_NODES = "yarn.timeline-service.es.nodes";

  public static final String TIMELINE_SERVICE_CLUSTER_NAME = "yarn.timeline-service.es.cluster.name";

  public static final String ES_BULK_EMPTY_INDEX = "{'index':{}}";

  public static final int ES_BATCH_SIZE = 10000;

  public static String ES_CLUSTER_NAME = "elasticsearch";

  public static String ES_INDEX = "timelineserver";

  public static String ES_TYPE_ENTITY = "entity";

  public static String ES_TYPE_DOMAIN = "domain";

  private static List<InetSocketAddress> nodes = new ArrayList<>();

  private BlockingQueue<TimelineEntity> esEntities = new LinkedBlockingDeque<>();

  private BlockingQueue<TimelineDomain> esDomains = new LinkedBlockingDeque<>();

  private Timer timer;

  public ElasticSearchTimelineStore() {
    super(ElasticSearchTimelineStore.class.getName());
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    String esNodes = conf.get(TIMELINE_SERVICE_ES_NODES);
    if (esNodes != null) {
      String[] array = esNodes.split(",");
      for (String address : array)
        nodes.add(NetUtils.createSocketAddr(address));
    }
    ES_CLUSTER_NAME = conf.get(TIMELINE_SERVICE_CLUSTER_NAME);

    TimerTask task = new TimerTask() {
      @Override
      public void run() {
        Gson gson = new Gson();
        // Deal with entity
        TimelineEntity entity;
        List<TimelineEntity> entities;
        int i = 0;
        BulkRequestBuilder bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes).prepareBulk();
        while ((entity = esEntities.poll()) != null) {
          String docId = entity.getEntityType() + "_" + entity.getEntityId();

          // Just for es mapping
          if ("TEZ_APPLICATION".equals(entity.getEntityType())) {
            Map<String, String> config = (Map<String, String>) entity.getOtherInfo().get("config");
            Map<String, String> newConfig = new HashMap<>(config.size());
            for (String key : config.keySet()) {
              newConfig.put(key.replaceAll("\\.", "__"), config.get(key));
            }
            entity.getOtherInfo().put("config", newConfig);
          }

          String json = gson.toJson(entity);
          bulkRequest.add(new UpdateRequest(ES_INDEX, ES_TYPE_ENTITY, docId).doc(json).upsert(json));
          // bulkRequest.add(new IndexRequest(ES_INDEX, ES_TYPE_ENTITY, docId).source(gson.toJson(entity)));
          i++;
          if (i >= ES_BATCH_SIZE) {
            BulkResponse bulkResponse = bulkRequest.get();
            if (bulkResponse.hasFailures()) {
              LOG.error("Es Exception:" + bulkResponse.buildFailureMessage());
            }

            i = 0;
            bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes).prepareBulk();
          }
        }
        if (i > 0) {
          BulkResponse bulkResponse = bulkRequest.get();
          if (bulkResponse.hasFailures()) {
            LOG.error("Es Exception:" + bulkResponse.buildFailureMessage());
          }
          i = 0;
          bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes).prepareBulk();
        }

        // Deal with domain
        TimelineDomain domain;
        while ((domain = esDomains.poll()) != null) {
          String json = gson.toJson(domain);
          bulkRequest.add(new UpdateRequest(ES_INDEX, ES_TYPE_DOMAIN, domain.getId()).doc(json).upsert(json));
          i++;
          if (i >= ES_BATCH_SIZE) {
            BulkResponse bulkResponse = bulkRequest.get();
            if (bulkResponse.hasFailures()) {
              LOG.error("Es Exception:" + bulkResponse.buildFailureMessage());
            }

            i = 0;
            bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes).prepareBulk();
          }
        }
        if (i > 0) {
          BulkResponse bulkResponse = bulkRequest.get();
          if (bulkResponse.hasFailures()) {
            LOG.error("Es Exception:" + bulkResponse.buildFailureMessage());
          }
        }
      }
    };
    timer = new Timer();
    long intevalPeriod = 1 * 1000;
    // schedules the task to be run in an interval
    timer.scheduleAtFixedRate(task, 0, intevalPeriod);
    super.serviceInit(conf);
  }

  @Override
  protected void serviceStop() throws Exception {
    timer.cancel();

    super.serviceStop();
  }

  @Override
  public TimelinePutResponse put(TimelineEntities entities) throws IOException {
    for (TimelineEntity entity : entities.getEntities()) {
      try {
        esEntities.put(entity);
      } catch (InterruptedException e) {
        LOG.error("put entity error!", e);
      }
    }
    return new TimelinePutResponse();
  }

  @Override
  public void put(TimelineDomain domain) throws IOException {
    esDomains.add(domain);
  }

  private String[] esEntityFields(EnumSet<Field> fieldsToRetrieve) {
    if (fieldsToRetrieve != null) {
      String[] fields = new String[fieldsToRetrieve.size()];
      int i = 0;
      for (Field f : fieldsToRetrieve) {
        switch (f) {
          case EVENTS:
            fields[i++] = "events";
            break;
          case RELATED_ENTITIES:
            fields[i++] = "relatedEntities";
            break;
          case PRIMARY_FILTERS:
            fields[i++] = "primaryFilters";
            break;
          case OTHER_INFO:
            fields[i++] = "otherInfo";
            break;
          case LAST_EVENT_ONLY:
            fields[i++] = "lastEventOnly";
            break;
        }
      }
      return fields;
    }
    return null;
  }

  private SearchResponse searchEsEntity(BoolQueryBuilder queryBuilder, String[] fields) {
    SearchRequestBuilder searchRequestBuilder = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_ENTITY)
            .setQuery(queryBuilder);

    if (fields != null)
      searchRequestBuilder.setFetchSource(fields, null);

    return searchRequestBuilder.get();
  }

  private TimelineEntity parseEntity(Map<String, Object> source, EnumSet<Field> fieldsToRetrieve) {
    TimelineEntity entity = new TimelineEntity();

    /*
    Type type;
    if (fieldsToRetrieve != null) {
      for (Field field : fieldsToRetrieve) {
        switch (field) {
          case EVENTS:
            type = new TypeToken<List<TimelineEvent>>() {
            }.getType();
            List<TimelineEvent> events = gson.fromJson(source.get("events").toString(), type);
            timelineEntity.setEvents(events);
            break;
          case RELATED_ENTITIES:
            type = new TypeToken<Map<String, Set<String>>>() {
            }.getType();
            Map<String, Set<String>> relatedEntities = gson.fromJson(source.get("relatedEntities").toString(), type);
            timelineEntity.setRelatedEntities(relatedEntities);
            break;
          case PRIMARY_FILTERS:
            if (source.get("primaryFilters") != null) {
              Map<String, ArrayList<Object>> esPrimaryFilters = (Map<String, ArrayList<Object>>) source.get("primaryFilters");
              Map<String, Set<Object>> primaryFilters = new HashMap<>();
              for (Map.Entry<String, ArrayList<Object>> en : esPrimaryFilters.entrySet())
                primaryFilters.put(en.getKey(), new HashSet<>(en.getValue()));

              timelineEntity.setPrimaryFilters(primaryFilters);
            }
            break;
          case OTHER_INFO:
            if (source.get("otherInfo") != null) {
              Map<String, Object> otherInfo = (Map<String, Object>) source.get("otherInfo");
              timelineEntity.setOtherInfo(otherInfo);
            }
            break;
        }
      }
    } else {*/
    entity.setEntityType((String) source.get("entityType"));
    entity.setEntityId((String) source.get("entityId"));
    entity.setStartTime((Long) source.get("startTime"));

    if (source.get("events") != null) {
      ArrayList<Object> esEvents = (ArrayList<Object>) source.get("events");
      List<TimelineEvent> events = new ArrayList<>();
      for (Object obj : esEvents) {
        HashMap<String, Object> esEvent = (HashMap<String, Object>) obj;
        TimelineEvent event = new TimelineEvent();
        event.setEventInfo((Map<String, Object>) esEvent.get("eventInfo"));
        event.setEventType((String) esEvent.get("eventType"));
        event.setTimestamp((Long) esEvent.get("timestamp"));
        events.add(event);
      }
      entity.setEvents(events);
    }

    if (source.get("relatedEntities") != null) {
      Map<String, ArrayList<String>> esRelatedEntities = (Map<String, ArrayList<String>>) source.get("relatedEntities");
      Map<String, Set<String>> relatedEntities = new HashMap<>();
      for (Map.Entry<String, ArrayList<String>> en : esRelatedEntities.entrySet()) {
        relatedEntities.put(en.getKey(), new HashSet<>(en.getValue()));
      }
      entity.setRelatedEntities(relatedEntities);
    }

    if (source.get("primaryFilters") != null) {
      Map<String, ArrayList<Object>> esPrimaryFilters = (Map<String, ArrayList<Object>>) source.get("primaryFilters");
      Map<String, Set<Object>> primaryFilters = new HashMap<>();
      for (Map.Entry<String, ArrayList<Object>> en : esPrimaryFilters.entrySet())
        primaryFilters.put(en.getKey(), new HashSet<>(en.getValue()));

      entity.setPrimaryFilters(primaryFilters);
    }

    if (source.get("otherInfo") != null) {
      Map<String, Object> otherInfo = (Map<String, Object>) source.get("otherInfo");

      // Just for es mapping
      if ("TEZ_APPLICATION".equals(entity.getEntityType())) {
        Map<String, String> config = (Map<String, String>) otherInfo.get("config");
        Map<String, String> newConfig = new HashMap<>(config.size());
        for (String key : config.keySet()) {
          newConfig.put(key.replaceAll("__", "\\."), config.get(key));
        }
        otherInfo.put("config", newConfig);
      }

      entity.setOtherInfo(otherInfo);
    }

    entity.setDomainId((String) source.get("domainId"));
//    }
    return entity;

  }

  @Override
  public TimelineEntities getEntities(String entityType, Long limit, Long windowStart,
                                      Long windowEnd, String fromId, Long fromTs, NameValuePair primaryFilter,
                                      Collection<NameValuePair> secondaryFilters, EnumSet<Field> fieldsToRetrieve)
          throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    if (entityType != null)
      queryBuilder.must(QueryBuilders.termQuery("entityType", entityType));
    // TODO window 查询的是哪个字段
    if (windowStart != null)
      queryBuilder.filter(QueryBuilders.rangeQuery("starttime").gte(windowStart));
    if (windowEnd != null)
      queryBuilder.filter(QueryBuilders.rangeQuery("starttime").lte(windowEnd));
    if (fromId != null)
      queryBuilder.must(QueryBuilders.termQuery("entityId", fromId));
    // TODO fromTs 这个是查询哪个字段？
    if (primaryFilter != null) {
      queryBuilder.must(QueryBuilders.termQuery("primaryFilters." + primaryFilter.getName(),
              primaryFilter.getValue()));
    }
    if (secondaryFilters != null) {
      for (NameValuePair filter : secondaryFilters) {
        queryBuilder.must(QueryBuilders.termQuery("primaryFilters." + filter.getName(),
                filter.getValue()));
      }
    }

//    String[] fields = esEntityFields(fieldsToRetrieve);
    SearchResponse response = searchEsEntity(queryBuilder, null);

    LOG.debug("getEntities response :" + response);

    TimelineEntities timelineEntities = new TimelineEntities();

    for (SearchHit hit : response.getHits().hits()) {
      Map<String, Object> source = hit.getSource();
      TimelineEntity timelineEntity = parseEntity(source, fieldsToRetrieve);

      timelineEntities.addEntity(timelineEntity);
    }

    return timelineEntities;
  }

  @Override
  public TimelineEntity getEntity(String entityId, String entityType,
                                  EnumSet<Field> fieldsToRetrieve) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    if (entityId != null)
      queryBuilder.must(QueryBuilders.termQuery("entityId", entityId));
    if (entityType != null)
      queryBuilder.must(QueryBuilders.termQuery("entityType", entityType));

//    String[] fields = esEntityFields(fieldsToRetrieve);
    SearchResponse response = searchEsEntity(queryBuilder, null);

    LOG.debug("getEntity response :" + response);

    if (response.getHits().totalHits() > 0) {
      SearchHit[] hits = response.getHits().hits();
      return parseEntity(hits[0].getSource(), fieldsToRetrieve);
    } else
      return null;
  }

  @Override
  public TimelineEvents getEntityTimelines(String entityType, SortedSet<String> entityIds,
                                           Long limit, Long windowStart, Long windowEnd, Set<String> eventTypes) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    if (entityType != null)
      queryBuilder.must(QueryBuilders.termQuery("entityType", entityType));
    // TODO window 查询的是哪个字段
    if (windowStart != null)
      queryBuilder.filter(QueryBuilders.rangeQuery("starttime").gte(windowStart));
    if (windowEnd != null)
      queryBuilder.filter(QueryBuilders.rangeQuery("starttime").lte(windowEnd));

    if (entityIds != null) {
      for (String id : entityIds) {
        queryBuilder.should(QueryBuilders.termQuery("entityId", id));
      }
    }
    if (eventTypes != null) {
      for (String eventType : eventTypes) {
        queryBuilder.must(QueryBuilders.termQuery("events.eventtype", eventType));
      }
    }

    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_ENTITY)
            .setQuery(queryBuilder)
            .setSize(limit.intValue()).get();

    LOG.debug("getEntityTimelines response :" + response);

    TimelineEvents events = new TimelineEvents();

    Gson gson = new Gson();
    for (SearchHit hit : response.getHits().hits()) {
      events.addEvent(gson.fromJson(hit.getSource().toString(), TimelineEvents.EventsOfOneEntity.class));
    }

    return events;
  }

  @Override
  public TimelineDomain getDomain(String domainId) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    queryBuilder.must(QueryBuilders.termQuery("id", domainId));


    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_DOMAIN)
            .setQuery(queryBuilder).get();

    LOG.debug("getDomain response :" + response);

    if (response.getHits().totalHits() > 0) {
      Gson gson = new Gson();
      SearchHit[] hits = response.getHits().hits();
      return gson.fromJson(hits[0].getSourceAsString(), TimelineDomain.class);
    } else
      return null;
  }

  @Override
  public TimelineDomains getDomains(String owner) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    queryBuilder.must(QueryBuilders.termQuery("owner", owner));


    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME, nodes)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_DOMAIN)
            .setQuery(queryBuilder).get();

    LOG.debug("getDomains response :" + response);

    TimelineDomains domains = new TimelineDomains();
    SearchHit[] hits = response.getHits().hits();
    for (SearchHit hit : hits) {
      Gson gson = new Gson();
      domains.addDomain(gson.fromJson(hit.getSourceAsString(), TimelineDomain.class));
    }

    return domains;
  }

  static class ESUtil {
    private static TransportClient transportClient = null;

    public static TransportClient getEsClient(String clusterName, List<InetSocketAddress> esNodes) {
      return getEsClient(clusterName, esNodes.toArray(new InetSocketAddress[esNodes.size()]));
    }

    public static TransportClient getEsClient(String clusterName, InetSocketAddress[] esNodes) {
      if (transportClient == null) {
        synchronized (ESUtil.class) {
          if (transportClient == null) {
            Settings setting =
                    Settings.settingsBuilder().put("cluster.name", clusterName)     //指定集群名称
                            .put("client.transport.sniff", true)    //启动嗅探功能
                            .build();
            transportClient = TransportClient.builder().settings(setting).build();
            for (InetSocketAddress node : esNodes) {
              transportClient.addTransportAddress(new InetSocketTransportAddress(node));
            }
          }
        }
      }
      return transportClient;
    }
  }
}
