package org.apache.hadoop.yarn.server.timeline;

import com.google.gson.*;
import org.apache.commons.lang.*;
import org.apache.commons.logging.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.net.*;
import org.apache.hadoop.service.*;
import org.apache.hadoop.yarn.api.records.timeline.*;
import org.elasticsearch.action.search.*;
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

  public static final String ES_BULK_EMPTY_INDEX = "{'index':{}}";

  public static final int ES_BATCH_SIZE = 10000;

  public static String ES_CLUSTER_NAME="elasticsearch";

  public static String ES_INDICE="timelineserver";

  public static String ES_TYPE_ENTITY="entity";

  public static String ES_TYPE_DOMAIN="domain";

  private static List<InetSocketAddress> nodes = new ArrayList<>();

  private BlockingQueue<TimelineEntity> esEntities = new LinkedBlockingDeque<>();

  private BlockingQueue<TimelineDomain> esDomains = new LinkedBlockingDeque<>();

  public ElasticSearchTimelineStore(String name) {
    super(ElasticSearchTimelineStore.class.getName());
  }

  @Override protected void serviceInit(Configuration conf) throws Exception {
    String esNodes = conf.get(TIMELINE_SERVICE_ES_NODES);
    if (esNodes != null) {
      String[] array = esNodes.split(",");
      for (String address : array)
        nodes.add(NetUtils.createSocketAddr(address));
    }



    TimerTask task = new TimerTask() {
      @Override public void run() {
        StringBuilder data = new StringBuilder();

        // Deal with entity
        TimelineEntity entity;
        int i = 0;
        while ((entity = esEntities.poll()) != null) {
          data.append(ES_BULK_EMPTY_INDEX);
          data.append(entity.toString());
          i++;
          if (i >= ES_BATCH_SIZE) {
            ESUtil.doPostOrPut("/"+ES_INDICE+"/"+ES_TYPE_ENTITY+"/_bulk", data.toString(), "POST");
            i = 0;
            data.setLength(0);
          }
        }
        if (i > 0) {
          ESUtil.doPostOrPut("/"+ES_INDICE+"/"+ES_TYPE_ENTITY+"/_bulk", data.toString(), "POST");
          i = 0;
          data.setLength(0);
        }

        // Deal with domain
        TimelineDomain domain;
        while ((domain = esDomains.poll()) != null) {
          data.append(ES_BULK_EMPTY_INDEX);
          data.append(domain.toString());
          i++;
          if (i >= ES_BATCH_SIZE) {
            ESUtil.doPostOrPut("/"+ES_INDICE+"/"+ES_TYPE_DOMAIN+"/_bulk", data.toString(), "POST");
            i = 0;
            data.setLength(0);
          }
        }
        if (i > 0) {
          ESUtil.doPostOrPut("/"+ES_INDICE+"/"+ES_TYPE_DOMAIN+"/_bulk", data.toString(), "POST");
          i = 0;
          data.setLength(0);
        }

      }
    };
    Timer timer = new Timer();
    long intevalPeriod = 1 * 1000;
    // schedules the task to be run in an interval
    timer.scheduleAtFixedRate(task, 0, intevalPeriod);

    super.serviceInit(conf);
  }

  @Override protected void serviceStop() throws Exception {
    super.serviceStop();
  }

  @Override public TimelinePutResponse put(TimelineEntities entities) throws IOException {
    for (TimelineEntity entity : entities.getEntities()) {
      try {
        esEntities.put(entity);
      } catch (InterruptedException e) {
        LOG.error("put entity error!", e);
      }
    }
    return new TimelinePutResponse();
  }

  @Override public void put(TimelineDomain domain) throws IOException {
    esDomains.add(domain);
  }

  class EntityQueryBean {
    String entityType;
    String entityId;
  }

  @Override public TimelineEntities getEntities(String entityType, Long limit, Long windowStart,
      Long windowEnd, String fromId, Long fromTs, NameValuePair primaryFilter,
      Collection<NameValuePair> secondaryFilters, EnumSet<Field> fieldsToRetrieve)
      throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    queryBuilder.must(QueryBuilders.termQuery("entityType",entityType))
        // TODO window 查询的是哪个字段
        .filter(QueryBuilders.rangeQuery("starttime").gte(windowStart).lte(windowEnd))
        .must(QueryBuilders.termQuery("entity",fromId));
    // TODO fromTs 这个是查询哪个字段？
    if(primaryFilter != null){
      queryBuilder.must(QueryBuilders.termQuery("primaryfilters."+primaryFilter.getName(),
          primaryFilter.getValue()));
    }
    if(secondaryFilters != null) {
      for(NameValuePair filter:secondaryFilters) {
        queryBuilder.must(QueryBuilders.termQuery("primaryfilters."+filter.getName(),
          filter.getValue()));
      }
    }

    String[] fields = new String[fieldsToRetrieve.size()];
    int i=0;
    for(Field f:fieldsToRetrieve){
      fields[i++] = fieldsToRetrieve.toString();
    }
    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME,nodes)
        .prepareSearch(ES_TYPE_ENTITY)
        .setTypes(ES_INDICE)
        .setQuery(queryBuilder)
        .addFields(fields).get();

    LOG.debug("getEntities response :"+response);

    TimelineEntities timelineEntities = new TimelineEntities();
    Gson gson = new Gson();
    for(SearchHit hit:response.getHits().hits()) {
      timelineEntities.addEntity(gson.fromJson(hit.getSource().toString(), TimelineEntity.class));
    }

    return timelineEntities;
  }

  @Override public TimelineEntity getEntity(String entityId, String entityType,
      EnumSet<Field> fieldsToRetrieve) throws IOException {
    JsonObject match = new JsonObject();
    if (StringUtils.isEmpty(entityId))
      match.addProperty("entity", entityId);
    if (StringUtils.isEmpty(entityType))
      match.addProperty("entityType", entityType);
    JsonObject query = new JsonObject();
    query.add("match",match);
    JsonObject dsl = new JsonObject();
    dsl.add("query",query);
    ESUtil.doPostOrPut("/timelineserver/entity_search", dsl.toString(), "POST");
    return null;
  }

  @Override public TimelineEvents getEntityTimelines(String entityType, SortedSet<String> entityIds,
      Long limit, Long windowStart, Long windowEnd, Set<String> eventTypes) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    queryBuilder.must(QueryBuilders.termQuery("entityType",entityType))
        // TODO window 查询的是哪个字段
        .filter(QueryBuilders.rangeQuery("starttime").gte(windowStart).lte(windowEnd));

    if(entityIds != null) {
      for(String id:entityIds) {
        queryBuilder.should(QueryBuilders.termQuery("entity",id));
      }
    }
    if(eventTypes != null) {
      for(String eventType:eventTypes) {
        queryBuilder.must(QueryBuilders.termQuery("events.eventtype",eventType));
      }
    }

    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME,nodes)
        .prepareSearch(ES_TYPE_ENTITY)
        .setTypes(ES_INDICE)
        .setQuery(queryBuilder)
        .setSize(limit.intValue()).get();

    LOG.debug("getEntityTimelines response :"+response);

    TimelineEvents events = new TimelineEvents();

    Gson gson = new Gson();
    for(SearchHit hit:response.getHits().hits()) {
      events.addEvent(gson.fromJson(hit.getSource().toString(), TimelineEvents.EventsOfOneEntity.class));
    }

    return events;
  }

  @Override public TimelineDomain getDomain(String domainId) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    queryBuilder.must(QueryBuilders.termQuery("domainId",domainId));


    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME,nodes)
        .prepareSearch(ES_TYPE_DOMAIN)
        .setTypes(ES_INDICE)
        .setQuery(queryBuilder).get();

    LOG.debug("getDomain response :"+response);

    TimelineDomain domain = new TimelineDomain();
    SearchHit[] hits = response.getHits().hits();
    if(hits.length>0) {
      Gson gson = new Gson();
      domain = gson.fromJson(hits[0].getSource().toString(), TimelineDomain.class);
    }

    return domain;
  }

  @Override public TimelineDomains getDomains(String owner) throws IOException {
    BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
    queryBuilder.must(QueryBuilders.termQuery("owner",owner));


    SearchResponse response = ESUtil.getEsClient(ES_CLUSTER_NAME,nodes)
        .prepareSearch(ES_TYPE_DOMAIN)
        .setTypes(ES_INDICE)
        .setQuery(queryBuilder).get();

    LOG.debug("getDomains response :"+response);

    TimelineDomains domains = new TimelineDomains();
    TimelineDomain domain = new TimelineDomain();
    SearchHit[] hits = response.getHits().hits();
    for(SearchHit hit:hits ) {
      Gson gson = new Gson();
      domains.addDomain(gson.fromJson(hit.getSource().toString(), TimelineDomain.class));
    }

    return domains;
  }

  static class ESUtil {
    private static HttpURLConnection buildConnection(String uri, String method, int timeout,
        boolean usecaches) throws IOException {
      Collections.shuffle(nodes);
      for (InetSocketAddress node : nodes) {
        URL url = new URL("http", node.getHostString(), node.getPort(), uri);
        //此处的urlConnection对象实际上是根据URL的请求协议(此处是http)生成的URLConnection类的子类HttpURLConnection,
        //故此处最好将其转化为HttpURLConnection类型的对象
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        //2.处理设置参数和一般请求属性
        //2.1设置参数
        //可以根据请求的需要设置参数
        conn.setRequestMethod(method); //默认为GET 所以GET不设置也行
        conn.setUseCaches(usecaches);
        conn.setConnectTimeout(timeout); //请求超时时间

        //2.2请求属性
        //设置通用的请求属性 更多的头字段信息可以查阅HTTP协议
        conn.setRequestProperty("accept", "*/*");
        conn.setRequestProperty("connection", "Keep-Alive");
        conn.setRequestProperty("Content-Type", " application/json");//设定 请求格式 json，也可以设定xml格式的
        conn.setRequestProperty("Accept-Charset", "utf-8");  //设置编码语言
        return conn;
      }
      throw new IOException("Cannot create http connection!");
    }

    private static String parseResponse(HttpURLConnection conn) throws IOException {
      //4.远程对象变为可用。远程对象的头字段和内容变为可访问。
      //4.1获取HTTP 响应消息获取状态码
      if (conn.getResponseCode() == 200) {
        //4.2获取响应的头字段
        Map<String, List<String>> headers = conn.getHeaderFields();
        System.out.println(headers); //输出头字段

        //4.3获取响应正文
        BufferedReader reader = null;
        StringBuffer resultBuffer = new StringBuffer();
        String tempLine = null;

        reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        while ((tempLine = reader.readLine()) != null) {
          resultBuffer.append(tempLine);
        }
        //System.out.println(resultBuffer);
        reader.close();
        return resultBuffer.toString();
      }
      throw new IOException("parse response error!");
    }

    public static String doGet(String uri, String params) {
      String realUrl = uri + "?" + params;

      try {
        HttpURLConnection conn = buildConnection(realUrl, "GET", 10000, false);
        conn.connect();
        return parseResponse(conn);
      } catch (MalformedURLException e) {
        // TODO 自动生成的 catch 块
        e.printStackTrace();
      } catch (IOException e) {
        // TODO 自动生成的 catch 块
        e.printStackTrace();
      }
      return null;
    }

    public static String doPostOrPut(String uri, String data, String method) {
      try {
        HttpURLConnection conn = buildConnection(uri, method, 10000, false);

        //2.3设置请求正文 即要提交的数据
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(conn.getOutputStream()));
        pw.print(data);
        pw.flush();
        pw.close();

        conn.connect();
        return parseResponse(conn);
      } catch (MalformedURLException e) {
        // TODO 自动生成的 catch 块
        e.printStackTrace();
      } catch (IOException e) {
        // TODO 自动生成的 catch 块
        e.printStackTrace();
      }
      return null;
    }

    // ===============================
    private static TransportClient transportClient = null;

    public static TransportClient getEsClient(String clusterName, List<InetSocketAddress> esNodes) {
      return getEsClient(clusterName,(InetSocketAddress[])esNodes.toArray());
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
