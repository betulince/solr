/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.admin;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.CommonTestInjection;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.PrometheusResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.util.stats.MetricUtils;

/** Request handler to return metrics */
public class MetricsHandler extends RequestHandlerBase implements PermissionNameProvider {
  final SolrMetricManager metricManager;

  public static final String COMPACT_PARAM = "compact";
  public static final String PREFIX_PARAM = "prefix";
  public static final String REGEX_PARAM = "regex";
  public static final String PROPERTY_PARAM = "property";
  public static final String REGISTRY_PARAM = "registry";
  public static final String GROUP_PARAM = "group";
  public static final String KEY_PARAM = "key";
  public static final String EXPR_PARAM = "expr";
  public static final String TYPE_PARAM = "type";
  public static final String PROMETHEUS_METRICS_WT = "prometheus";

  public static final String ALL = "all";

  private static final Pattern KEY_SPLIT_REGEX =
      Pattern.compile("(?<!" + Pattern.quote("\\") + ")" + Pattern.quote(":"));
  private final CoreContainer cc;
  private final Map<String, String> injectedSysProps = CommonTestInjection.injectAdditionalProps();
  private final boolean enabled;

  public MetricsHandler(CoreContainer coreContainer) {
    this.metricManager = coreContainer.getMetricManager();
    this.cc = coreContainer;
    this.enabled = coreContainer.getConfig().getMetricsConfig().isEnabled();
  }

  public MetricsHandler(SolrMetricManager metricManager) {
    this.metricManager = metricManager;
    this.cc = null;
    this.enabled = true;
  }

  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public Name getPermissionName(AuthorizationContext request) {
    return Name.METRICS_READ_PERM;
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    if (metricManager == null) {
      throw new SolrException(
          SolrException.ErrorCode.INVALID_STATE, "SolrMetricManager instance not initialized");
    }

    if (cc != null && AdminHandlersProxy.maybeProxyToNodes(req, rsp, cc)) {
      return; // Request was proxied to other node
    }
    SolrRequestInfo.setRequestInfo(new SolrRequestInfo(req, rsp));
    try {
      handleRequest(req.getParams(), (k, v) -> rsp.add(k, v));
    } finally {
      SolrRequestInfo.clearRequestInfo();
    }
  }

  private void handleRequest(SolrParams params, BiConsumer<String, Object> consumer) {
    NamedList<Object> response;

    if (!enabled) {
      consumer.accept("error", "metrics collection is disabled");
      return;
    }

    if (PROMETHEUS_METRICS_WT.equals(params.get(CommonParams.WT))) {
      response = handlePrometheusExport(params);
      consumer.accept("metrics", response);
      return;
    }
    String[] keys = params.getParams(KEY_PARAM);
    if (keys != null && keys.length > 0) {
      handleKeyRequest(keys, consumer);
      return;
    }
    String[] exprs = params.getParams(EXPR_PARAM);
    if (exprs != null && exprs.length > 0) {
      handleExprRequest(exprs, consumer);
      return;
    }

    response = handleDropwizardRegistry(params);

    consumer.accept("metrics", response);
  }

  private NamedList<Object> handleDropwizardRegistry(SolrParams params) {
    boolean compact = params.getBool(COMPACT_PARAM, true);
    MetricFilter mustMatchFilter = parseMustMatchFilter(params);
    Predicate<CharSequence> propertyFilter = parsePropertyFilter(params);
    List<MetricType> metricTypes = parseMetricTypes(params);
    List<MetricFilter> metricFilters =
        metricTypes.stream().map(MetricType::asMetricFilter).collect(Collectors.toList());
    Set<String> requestedRegistries = parseRegistries(params);

    NamedList<Object> response = new SimpleOrderedMap<>();
    for (String registryName : requestedRegistries) {
      MetricRegistry registry = metricManager.registry(registryName);
      SimpleOrderedMap<Object> result = new SimpleOrderedMap<>();

      MetricUtils.toMaps(
          registry,
          metricFilters,
          mustMatchFilter,
          propertyFilter,
          false,
          false,
          compact,
          false,
          (k, v) -> result.add(k, v));
      if (result.size() > 0) {
        response.add(registryName, result);
      }
    }
    return response;
  }

  private NamedList<Object> handlePrometheusExport(SolrParams params) {
    NamedList<Object> response = new SimpleOrderedMap<>();
    MetricFilter mustMatchFilter = parseMustMatchFilter(params);
    Predicate<CharSequence> propertyFilter = parsePropertyFilter(params);
    List<MetricType> metricTypes = parseMetricTypes(params);
    List<MetricFilter> metricFilters =
        metricTypes.stream().map(MetricType::asMetricFilter).collect(Collectors.toList());

    Set<String> requestedRegistries = parseRegistries(params);
    MetricRegistry mergedCoreRegistries = new MetricRegistry();

    for (String registryName : requestedRegistries) {
      MetricRegistry dropwizardRegistry = metricManager.registry(registryName);

      // Merge all core registries into a single registry and
      // append the core name to the metric to avoid duplicate metrics name
      if (registryName.startsWith("solr.core")) {
        mergedCoreRegistries.registerAll(getCoreNameFromRegistry(registryName), dropwizardRegistry);
        continue;
      }

      PrometheusResponseWriter.toPrometheus(
          dropwizardRegistry,
          registryName,
          metricFilters,
          mustMatchFilter,
          propertyFilter,
          false,
          false,
          true,
          (registry) -> {
            response.add(registryName, registry);
          });
    }

    if (!mergedCoreRegistries.getMetrics().isEmpty()) {
      PrometheusResponseWriter.toPrometheus(
          mergedCoreRegistries,
          "solr.core",
          metricFilters,
          mustMatchFilter,
          propertyFilter,
          false,
          false,
          true,
          (registry) -> {
            response.add("solr.core", registry);
          });
    }

    return response;
  }

  private static class MetricsExpr {
    Pattern registryRegex;
    MetricFilter metricFilter;
    Predicate<CharSequence> propertyFilter;
  }

  private void handleExprRequest(String[] exprs, BiConsumer<String, Object> consumer) {
    SimpleOrderedMap<Object> result = new SimpleOrderedMap<>();
    SimpleOrderedMap<Object> errors = new SimpleOrderedMap<>();
    List<MetricsExpr> metricsExprs = new ArrayList<>();

    for (String key : exprs) {
      if (key == null || key.isEmpty()) {
        continue;
      }
      String[] parts = KEY_SPLIT_REGEX.split(key);
      if (parts.length < 2 || parts.length > 3) {
        errors.add(key, "at least two and at most three colon-separated parts must be provided");
        continue;
      }
      MetricsExpr me = new MetricsExpr();
      me.registryRegex = Pattern.compile(unescape(parts[0]));
      me.metricFilter = new SolrMetricManager.RegexFilter(unescape(parts[1]));
      String propertyPart = parts.length > 2 ? unescape(parts[2]) : null;
      if (propertyPart == null) {
        me.propertyFilter = name -> true;
      } else {
        me.propertyFilter =
            new Predicate<>() {
              final Pattern pattern = Pattern.compile(propertyPart);

              @Override
              public boolean test(CharSequence charSequence) {
                return pattern.matcher(charSequence).matches();
              }
            };
      }
      metricsExprs.add(me);
    }
    // find matching registries first, to avoid scanning non-matching registries
    Set<String> matchingRegistries = new TreeSet<>();
    metricsExprs.forEach(
        me -> {
          metricManager
              .registryNames()
              .forEach(
                  name -> {
                    if (me.registryRegex.matcher(name).matches()) {
                      matchingRegistries.add(name);
                    }
                  });
        });
    for (String registryName : matchingRegistries) {
      MetricRegistry registry = metricManager.registry(registryName);
      for (MetricsExpr me : metricsExprs) {
        @SuppressWarnings("unchecked")
        SimpleOrderedMap<Object> perRegistryResult =
            (SimpleOrderedMap<Object>) result.get(registryName);
        final SimpleOrderedMap<Object> perRegistryTemp = new SimpleOrderedMap<>();
        // skip processing if not a matching registry
        if (!me.registryRegex.matcher(registryName).matches()) {
          continue;
        }
        MetricUtils.toMaps(
            registry,
            Collections.singletonList(MetricFilter.ALL),
            me.metricFilter,
            me.propertyFilter,
            false,
            false,
            true,
            false,
            (k, v) -> perRegistryTemp.add(k, v));
        // extracted some metrics and there's no entry for this registry yet
        if (perRegistryTemp.size() > 0) {
          if (perRegistryResult == null) { // new results for this registry
            result.add(registryName, perRegistryTemp);
          } else {
            // merge if needed
            for (Iterator<Map.Entry<String, Object>> it = perRegistryTemp.iterator();
                it.hasNext(); ) {
              Map.Entry<String, Object> entry = it.next();
              Object existing = perRegistryResult.get(entry.getKey());
              if (existing == null) {
                perRegistryResult.add(entry.getKey(), entry.getValue());
              }
            }
          }
        }
      }
    }
    consumer.accept("metrics", result);
    if (errors.size() > 0) {
      consumer.accept("errors", errors);
    }
  }

  private void handleKeyRequest(String[] keys, BiConsumer<String, Object> consumer) {
    SimpleOrderedMap<Object> result = new SimpleOrderedMap<>();
    SimpleOrderedMap<Object> errors = new SimpleOrderedMap<>();
    for (String key : keys) {
      if (key == null || key.isEmpty()) {
        continue;
      }
      String[] parts = KEY_SPLIT_REGEX.split(key);
      if (parts.length < 2 || parts.length > 3) {
        errors.add(key, "at least two and at most three colon-separated parts must be provided");
        continue;
      }
      final String registryName = unescape(parts[0]);
      final String metricName = unescape(parts[1]);
      final String propertyName = parts.length > 2 ? unescape(parts[2]) : null;
      if (!metricManager.hasRegistry(registryName)) {
        errors.add(key, "registry '" + registryName + "' not found");
        continue;
      }
      MetricRegistry registry = metricManager.registry(registryName);
      Metric m = registry.getMetrics().get(metricName);
      if (m == null) {
        errors.add(key, "metric '" + metricName + "' not found");
        continue;
      }
      Predicate<CharSequence> propertyFilter = MetricUtils.ALL_PROPERTIES;
      if (propertyName != null) {
        propertyFilter = propertyName::contentEquals;
        // use escaped versions
        key = parts[0] + ":" + parts[1];
      }
      if (injectedSysProps != null
          && SolrMetricManager.JVM_REGISTRY.equals(registryName)
          && "system.properties".equals(metricName)
          && injectedSysProps.containsKey(propertyName)) {
        result.add(
            registryName + ":" + metricName + ":" + propertyName,
            injectedSysProps.get(propertyName));
        continue;
      }
      MetricUtils.convertMetric(
          key,
          m,
          propertyFilter,
          false,
          true,
          true,
          false,
          ":",
          (k, v) -> {
            if ((v instanceof Map) && propertyName != null) {
              ((Map<?, ?>) v).forEach((k1, v1) -> result.add(k + ":" + k1, v1));
            } else if ((v instanceof MapWriter) && propertyName != null) {
              ((MapWriter) v)._forEachEntry((k1, v1) -> result.add(k + ":" + k1, v1));
            } else {
              result.add(k, v);
            }
          });
    }
    consumer.accept("metrics", result);
    if (errors.size() > 0) {
      consumer.accept("errors", errors);
    }
  }

  private static String unescape(String s) {
    if (s.indexOf('\\') == -1) {
      return s;
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\') {
        if (i < s.length() - 1 && s.charAt(i + 1) == ':') {
          continue;
        }
      }
      sb.append(c);
    }
    return sb.toString();
  }

  private MetricFilter parseMustMatchFilter(SolrParams params) {
    String[] prefixes = params.getParams(PREFIX_PARAM);
    MetricFilter prefixFilter = null;
    if (prefixes != null && prefixes.length > 0) {
      Set<String> prefixSet = new HashSet<>();
      for (String prefix : prefixes) {
        prefixSet.addAll(StrUtils.splitSmart(prefix, ','));
      }
      prefixFilter = new SolrMetricManager.PrefixFilter(prefixSet);
    }
    String[] regexes = params.getParams(REGEX_PARAM);
    MetricFilter regexFilter = null;
    if (regexes != null && regexes.length > 0) {
      regexFilter = new SolrMetricManager.RegexFilter(regexes);
    }
    MetricFilter mustMatchFilter;
    if (prefixFilter == null && regexFilter == null) {
      mustMatchFilter = MetricFilter.ALL;
    } else {
      if (prefixFilter == null) {
        mustMatchFilter = regexFilter;
      } else if (regexFilter == null) {
        mustMatchFilter = prefixFilter;
      } else {
        mustMatchFilter = new SolrMetricManager.OrFilter(prefixFilter, regexFilter);
      }
    }
    return mustMatchFilter;
  }

  private Predicate<CharSequence> parsePropertyFilter(SolrParams params) {
    String[] props = params.getParams(PROPERTY_PARAM);
    if (props == null || props.length == 0) {
      return MetricUtils.ALL_PROPERTIES;
    }
    final Set<String> filter = new HashSet<>();
    for (String prop : props) {
      if (prop != null && !prop.trim().isEmpty()) {
        filter.add(prop.trim());
      }
    }
    if (filter.isEmpty()) {
      return MetricUtils.ALL_PROPERTIES;
    } else {
      return (name) -> filter.contains(name.toString());
    }
  }

  private Set<String> parseRegistries(SolrParams params) {
    String[] groupStr = params.getParams(GROUP_PARAM);
    String[] registryStr = params.getParams(REGISTRY_PARAM);
    return parseRegistries(groupStr, registryStr);
  }

  public Set<String> parseRegistries(String[] groupStr, String[] registryStr) {
    if ((groupStr == null || groupStr.length == 0)
        && (registryStr == null || registryStr.length == 0)) {
      // return all registries
      return metricManager.registryNames();
    }
    boolean allRegistries = false;
    Set<String> initialPrefixes = Collections.emptySet();
    if (groupStr != null && groupStr.length > 0) {
      initialPrefixes = new HashSet<>();
      for (String g : groupStr) {
        List<String> split = StrUtils.splitSmart(g, ',');
        for (String s : split) {
          if (s.trim().equals(ALL)) {
            allRegistries = true;
            break;
          }
          initialPrefixes.add(SolrMetricManager.enforcePrefix(s.trim()));
        }
        if (allRegistries) {
          return metricManager.registryNames();
        }
      }
    }

    if (registryStr != null && registryStr.length > 0) {
      if (initialPrefixes.isEmpty()) {
        initialPrefixes = new HashSet<>();
      }
      for (String r : registryStr) {
        List<String> split = StrUtils.splitSmart(r, ',');
        for (String s : split) {
          if (s.trim().equals(ALL)) {
            allRegistries = true;
            break;
          }
          initialPrefixes.add(SolrMetricManager.enforcePrefix(s.trim()));
        }
        if (allRegistries) {
          return metricManager.registryNames();
        }
      }
    }
    Set<String> validRegistries = new HashSet<>();
    for (String r : metricManager.registryNames()) {
      for (String prefix : initialPrefixes) {
        if (r.startsWith(prefix)) {
          validRegistries.add(r);
          break;
        }
      }
    }
    return validRegistries;
  }

  private List<MetricType> parseMetricTypes(SolrParams params) {
    String[] typeStr = params.getParams(TYPE_PARAM);
    List<String> types = Collections.emptyList();
    if (typeStr != null && typeStr.length > 0) {
      types = new ArrayList<>();
      for (String type : typeStr) {
        types.addAll(StrUtils.splitSmart(type, ','));
      }
    }

    // include all metrics by default
    List<MetricType> metricTypes = Collections.singletonList(MetricType.all);
    try {
      if (types.size() > 0) {
        metricTypes =
            types.stream().map(String::trim).map(MetricType::valueOf).collect(Collectors.toList());
      }
    } catch (IllegalArgumentException e) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "Invalid metric type in: "
              + types
              + " specified. Must be one of "
              + MetricType.SUPPORTED_TYPES_MSG,
          e);
    }
    return metricTypes;
  }

  private String getCoreNameFromRegistry(String registryName) {
    String coreName = registryName.substring(registryName.indexOf('.') + 1);
    return coreName.replace(".", "_");
  }

  @Override
  public String getDescription() {
    return "A handler to return all the metrics gathered by Solr";
  }

  @Override
  public Category getCategory() {
    return Category.ADMIN;
  }

  enum MetricType {
    histogram(Histogram.class),
    meter(Meter.class),
    timer(Timer.class),
    counter(Counter.class),
    gauge(Gauge.class),
    all(null);

    public static final String SUPPORTED_TYPES_MSG = EnumSet.allOf(MetricType.class).toString();

    private final Class<? extends Metric> klass;

    MetricType(Class<? extends Metric> klass) {
      this.klass = klass;
    }

    public MetricFilter asMetricFilter() {
      return (name, metric) -> klass == null || klass.isInstance(metric);
    }
  }
}
