package org.apache.hadoop.yarn.server.timeline;

import com.google.gson.Gson;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.timeline.*;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Created by wankun on 2018/2/6.
 */
public class ElasticSearchTimelineStore extends AbstractService implements TimelineStore {

  private static final Log LOG = LogFactory.getLog(ElasticSearchTimelineStore.class);

  public static final String TIMELINE_SERVICE_ES_NODES = "yarn.timeline-service.es.nodes";

  public static final String TIMELINE_SERVICE_CLUSTER_NAME = "yarn.timeline-service.es.cluster.name";

  public static final int ES_BATCH_SIZE = 10000;

  public static String ES_CLUSTER_NAME;

  public static String ES_INDEX = "timelineserver";

  public static String ES_TYPE_ENTITY = "entity";

  public static String ES_TYPE_DOMAIN = "domain";

  private static List<InetSocketAddress> ES_NODES = new ArrayList<>();

  private BlockingQueue<TimelineEntity> esEntities = new LinkedBlockingDeque<>();

  private BlockingQueue<TimelineDomain> esDomains = new LinkedBlockingDeque<>();

  private Thread entityPoster;
  private Thread domainPoster;

  public ElasticSearchTimelineStore() {
    super(ElasticSearchTimelineStore.class.getName());
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    String esNodes = conf.get(TIMELINE_SERVICE_ES_NODES);
    if (esNodes != null) {
      String[] array = esNodes.split(",");
      for (String address : array)
        ES_NODES.add(NetUtils.createSocketAddr(address));
    }
    ES_CLUSTER_NAME = conf.get(TIMELINE_SERVICE_CLUSTER_NAME);

    entityPoster = new Thread(new Runnable() {
      @Override
      public void run() {
        Gson gson = new Gson();
        // Deal with entity
        TimelineEntity entity;
        int i = 0;
        long ts1 = System.currentTimeMillis();
        BulkRequestBuilder bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES).prepareBulk();
        while (true) {
          try {
            entity = esEntities.take();
            String docId = entity.getEntityType() + "_" + entity.getEntityId();

            // Just for es mapping
            if ("TEZ_APPLICATION".equals(entity.getEntityType())) {
              Map<String, String> config = (Map<String, String>) entity.getOtherInfo().get("config");
              Map<String, String> newConfig = new HashMap<>(config.size());
              for (String key : config.keySet()) {
                newConfig.put(key.replaceAll("\\.", "__"), config.get(key));
              }
              entity.getOtherInfo().put("config", newConfig);
              if (entity.getStartTime() == null)
                entity.setStartTime(System.currentTimeMillis());
            }

            String json = gson.toJson(entity);
            bulkRequest.add(new UpdateRequest(ES_INDEX, ES_TYPE_ENTITY, docId).doc(json).upsert(json));
            LOG.debug("add entity id : " + docId);
            if ("YARN_APPLICATION".equals(entity.getEntityType()))
              LOG.info("YARN_APPLICATION : " + json);
            i++;
            if (i >= ES_BATCH_SIZE || System.currentTimeMillis() > (ts1 + 10000)) {
              long ts2 = System.currentTimeMillis();
              BulkResponse bulkResponse = bulkRequest.get();
              if (bulkResponse.hasFailures()) {
                LOG.error("Es Exception:" + bulkResponse.buildFailureMessage());
              }
              long ts3 = System.currentTimeMillis();
              LOG.info("entity poster : prepare " + (ts2 - ts1) + ", post " + (ts3 - ts2));
              if ((ts3 - ts2) > 30000 || bulkResponse.hasFailures())
                ESUtil.transportClient = null;

              ts1 = System.currentTimeMillis();
              i = 0;
              bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES).prepareBulk();
            }
          } catch (InterruptedException e) {
            LOG.error("InterruptedException Exception:" + e);
          }
        }
      }
    });


    entityPoster.start();
    domainPoster = new Thread(new Runnable() {
      @Override
      public void run() {
        Gson gson = new Gson();

        // Deal with domain
        TimelineDomain domain;
        int i = 0;
        long ts1 = System.currentTimeMillis();
        BulkRequestBuilder bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES).prepareBulk();
        while (true) {
          try {
            domain = esDomains.take();
            String json = gson.toJson(domain);
            bulkRequest.add(new UpdateRequest(ES_INDEX, ES_TYPE_DOMAIN, domain.getId()).doc(json).upsert(json));
            i++;
            if (i >= ES_BATCH_SIZE || System.currentTimeMillis() > (ts1 + 10000)) {
              long ts2 = System.currentTimeMillis();
              BulkResponse bulkResponse = bulkRequest.get();
              if (bulkResponse.hasFailures()) {
                LOG.error("Es Exception:" + bulkResponse.buildFailureMessage());
              }
              long ts3 = System.currentTimeMillis();
              LOG.info("domain poster : prepare " + (ts2 - ts1) + ", post " + (ts3 - ts2));
              if ((ts3 - ts2) > 30000 || bulkResponse.hasFailures())
                ESUtil.transportClient = null;

              ts1 = System.currentTimeMillis();
              i = 0;
              bulkRequest = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES).prepareBulk();
            }
          } catch (InterruptedException e) {
            LOG.error("InterruptedException Exception:" + e);
          }

        }
      }
    });
    domainPoster.start();


    super.serviceInit(conf);
  }

  @Override
  protected void serviceStop() throws Exception {
    try {
      entityPoster.interrupt();
    } catch (Exception e) {
      try {
        entityPoster.join();
      } catch (Exception e2) {
      }
    }

    try {
      domainPoster.interrupt();
    } catch (Exception e) {
      try {
        domainPoster.join();
      } catch (Exception e2) {
      }
    }

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

  private SearchResponse searchEsEntity(BoolQueryBuilder queryBuilder, String[] fields, Long limit) {
    SearchRequestBuilder searchRequestBuilder = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_ENTITY)
            .setQuery(queryBuilder);

    SortBuilder sort = SortBuilders.fieldSort("startTime")
            .order(SortOrder.DESC)
            .unmappedType("Long");

    if (fields != null)
      searchRequestBuilder.setFetchSource(fields, null);
    if (limit != null)
      searchRequestBuilder.setSize(limit.intValue());
    searchRequestBuilder.addSort(sort);

    return searchRequestBuilder.get();
  }

  private TimelineEntity parseEntity(Map<String, Object> source, EnumSet<Field> fieldsToRetrieve) {
    TimelineEntity entity = new TimelineEntity();

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
      queryBuilder.filter(QueryBuilders.rangeQuery("entityId").lt(fromId));
    // TODO fromTs 这个是查询哪个字段？
    if (primaryFilter != null) {
      queryBuilder.must(QueryBuilders.termQuery("primaryFilters." + primaryFilter.getName(),
              primaryFilter.getValue()));
    }
    if (secondaryFilters != null) {
      for (NameValuePair filter : secondaryFilters) {
        if ("status".equals(filter.getName()) && "RUNNING".equals(filter.getValue()))
          queryBuilder.mustNot(QueryBuilders.existsQuery("primaryFilters.status"));
        else
          queryBuilder.filter(QueryBuilders.termQuery("primaryFilters." + filter.getName(),
                  filter.getValue()));
      }
    }

//    String[] fields = esEntityFields(fieldsToRetrieve);
    SearchResponse response = searchEsEntity(queryBuilder, null, limit);

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

    LOG.debug("getEntity queryBuilder : " + queryBuilder);
//    String[] fields = esEntityFields(fieldsToRetrieve);
    SearchResponse response = searchEsEntity(queryBuilder, null, 1l);

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

    SortBuilder sort = SortBuilders.fieldSort("startTime")
            .order(SortOrder.DESC)
            .unmappedType("Long");

    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_ENTITY)
            .setQuery(queryBuilder)
            .addSort(sort)
            .setSize(limit.intValue()).get();

    TimelineEvents events = new TimelineEvents();
    Gson gson = new Gson();
    for (SearchHit hit : response.getHits().hits())
      events.addEvent(gson.fromJson(hit.getSource().toString(), TimelineEvents.EventsOfOneEntity.class));

    return events;
  }

  @Override
  public TimelineDomain getDomain(String domainId) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    queryBuilder.must(QueryBuilders.termQuery("id", domainId));


    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_DOMAIN)
            .setQuery(queryBuilder).get();

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


    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME, ES_NODES)
            .prepareSearch(ES_INDEX)
            .setTypes(ES_TYPE_DOMAIN)
            .setQuery(queryBuilder).get();

    TimelineDomains domains = new TimelineDomains();
    SearchHit[] hits = response.getHits().hits();
    Gson gson = new Gson();
    for (SearchHit hit : hits)
      domains.addDomain(gson.fromJson(hit.getSourceAsString(), TimelineDomain.class));

    return domains;
  }

  static class ESUtil {
    public static TransportClient transportClient = null;

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
