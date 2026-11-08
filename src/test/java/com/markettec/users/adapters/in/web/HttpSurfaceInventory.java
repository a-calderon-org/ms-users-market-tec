package com.markettec.users.adapters.in.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Enumerates the route sources the DispatcherServlet consults: all {@link HandlerMapping} beans of
 * the context and its ancestors (the {@code detectAllHandlerMappings} lookup).
 *
 * <p><b>Only exact, verified implementation classes are enumerated.</b> A mapping is trusted to be
 * fully described by its registry only when its runtime class is <em>exactly</em> one of
 * {@link #ENUMERATORS} (fully qualified name; a subclass is never trusted, because it can resolve
 * handlers in {@code getHandlerInternal} without registering them). Every other mapping, including a
 * subclass of a known class, becomes an {@code UNENUMERABLE} entry that fails the comparison unless
 * the inventory authorizes it consciously.
 *
 * <p>For each enumerable mapping the inventory records:
 * <ul>
 *   <li>{@code MATCH}: the effective matching semantics (path-pattern vs. {@code PathMatcher},
 *       parser case sensitivity, optional trailing separator, path options, trailing-slash and suffix
 *       matching, path prefixes, content negotiation strategies, CORS source, interceptors);</li>
 *   <li>handler-method mappings: Spring's mapping-info rendering (methods, patterns, params, headers,
 *       consumes, produces, custom condition) plus the generic handler signature;</li>
 *   <li>functional routes: every leaf through the framework visitor, nested predicates included;</li>
 *   <li>URL mappings: each pattern with its handler type, plus root and default handlers.</li>
 * </ul>
 * The comparison is an exact multiset. Servlet filters, additional servlets and the separate
 * management listener are not HandlerMappings and are out of this inventory's scope.
 */
final class HttpSurfaceInventory {
    private HttpSurfaceInventory() { }

    private enum Kind { HANDLER_METHODS, ROUTER_FUNCTIONS, URL }

    /**
     * Verified enumerators, by exact runtime class. Each was checked to resolve handlers only from
     * the registry the inventory reads: the two handler-method mappings use Spring's
     * {@code lookupHandlerMethod} over {@code getHandlerMethods()}; Boot's welcome-page mappings
     * override {@code getHandlerInternal} but only serve their root handler, which is enumerated.
     */
    static final Map<String, Kind> ENUMERATORS = Map.of(
            RequestMappingHandlerMapping.class.getName(), Kind.HANDLER_METHODS,
            "org.springframework.boot.actuate.endpoint.web.servlet.AdditionalHealthEndpointPathsWebMvcHandlerMapping",
            Kind.HANDLER_METHODS,
            RouterFunctionMapping.class.getName(), Kind.ROUTER_FUNCTIONS,
            SimpleUrlHandlerMapping.class.getName(), Kind.URL,
            BeanNameUrlHandlerMapping.class.getName(), Kind.URL,
            "org.springframework.boot.autoconfigure.web.servlet.WelcomePageHandlerMapping", Kind.URL,
            "org.springframework.boot.autoconfigure.web.servlet.WelcomePageNotAcceptableHandlerMapping", Kind.URL);

    static List<String> effective(ApplicationContext context) {
        var entries = new ArrayList<String>();
        Map<String, HandlerMapping> mappings =
                BeanFactoryUtils.beansOfTypeIncludingAncestors(context, HandlerMapping.class, true, false);
        for (var bean : mappings.entrySet()) {
            String name = bean.getKey();
            HandlerMapping mapping = bean.getValue();
            Kind kind = ENUMERATORS.get(mapping.getClass().getName());
            if (kind == null) {
                entries.add("UNENUMERABLE " + name + " " + mapping.getClass().getName());
                continue;
            }
            entries.add("MATCH " + name + " " + matching((AbstractHandlerMapping) mapping));
            switch (kind) {
                case HANDLER_METHODS -> {
                    for (Map.Entry<?, HandlerMethod> e : ((AbstractHandlerMethodMapping<?>) mapping).getHandlerMethods().entrySet())
                        entries.add("MVC " + name + " " + e.getKey() + " -> " + e.getValue().getMethod().toGenericString());
                }
                case ROUTER_FUNCTIONS -> {
                    RouterFunction<?> routes = ((RouterFunctionMapping) mapping).getRouterFunction();
                    if (routes != null) routes.accept(new Collector(name, entries));
                }
                case URL -> {
                    var url = (AbstractUrlHandlerMapping) mapping;
                    for (var e : url.getHandlerMap().entrySet())
                        entries.add("URL " + name + " " + e.getKey() + " -> " + type(e.getValue()));
                    if (url.getRootHandler() != null) entries.add("URL-ROOT " + name + " -> " + type(url.getRootHandler()));
                }
            }
            Object fallback = ((AbstractHandlerMapping) mapping).getDefaultHandler();
            if (fallback != null) entries.add("DEFAULT " + name + " -> " + type(fallback));
        }
        Collections.sort(entries);
        return entries;
    }

    /** Effective properties that decide which requests a registered pattern accepts. */
    @SuppressWarnings("deprecation")
    static String matching(AbstractHandlerMapping mapping) {
        var out = new StringBuilder();
        out.append("pathPatterns=").append(mapping.usesPathPatterns());
        PathPatternParser parser = mapping.getPatternParser();
        if (parser != null) {
            out.append(" caseSensitive=").append(parser.isCaseSensitive())
                    .append(" optionalTrailingSeparator=").append(parser.isMatchOptionalTrailingSeparator())
                    .append(" separator=").append(parser.getPathOptions().separator())
                    .append(" decodeSegments=").append(parser.getPathOptions().shouldDecodeAndParseSegments());
        } else {
            out.append(" pathMatcher=").append(mapping.getPathMatcher().getClass().getName())
                    .append(" urlDecode=").append(mapping.getUrlPathHelper().isUrlDecode())
                    .append(" removeSemicolons=").append(mapping.getUrlPathHelper().shouldRemoveSemicolonContent());
        }
        if (mapping instanceof RequestMappingHandlerMapping annotated) {
            out.append(" trailingSlash=").append(annotated.useTrailingSlashMatch())
                    .append(" suffix=").append(annotated.useSuffixPatternMatch())
                    .append(" registeredSuffix=").append(annotated.useRegisteredSuffixPatternMatch())
                    .append(" prefixes=").append(new TreeSet<>(annotated.getPathPrefixes().keySet()))
                    .append(" negotiation=").append(annotated.getContentNegotiationManager().getStrategies().stream()
                            .map(s -> s.getClass().getName()).sorted().toList());
        } else if (mapping instanceof AbstractUrlHandlerMapping url) {
            out.append(" trailingSlash=").append(url.useTrailingSlashMatch());
        }
        var cors = mapping.getCorsConfigurationSource();
        out.append(" cors=").append(cors == null ? "none" : cors instanceof UrlBasedCorsConfigurationSource source
                ? new TreeSet<>(source.getCorsConfigurations().keySet()).toString() : cors.getClass().getName());
        var interceptors = mapping.getAdaptedInterceptors(); // null when the mapping has none
        out.append(" interceptors=").append(interceptors == null ? List.of() : Arrays.stream(interceptors)
                .map(i -> i.getClass().getName()).sorted().toList());
        return out.toString();
    }

    /** Fails with the full difference when the effective surface is not exactly the expected multiset. */
    static void verify(ApplicationContext context, List<String> expected) {
        List<String> actual = effective(context);
        var sortedExpected = new ArrayList<>(expected);
        Collections.sort(sortedExpected);
        if (actual.equals(sortedExpected)) return;
        var unexpected = new ArrayList<>(actual);
        sortedExpected.forEach(unexpected::remove);
        var missing = new ArrayList<>(sortedExpected);
        actual.forEach(missing::remove);
        throw new AssertionError("HTTP surface differs from inventory.\nUNINVENTORIED: " + unexpected
                + "\nMISSING: " + missing);
    }

    private static String type(Object handler) {
        return handler instanceof HandlerMethod method ? method.getMethod().toGenericString() : handler.getClass().getName();
    }

    private static final class Collector implements RouterFunctions.Visitor {
        private final String bean;
        private final List<String> entries;
        private final Deque<String> nested = new ArrayDeque<>();

        Collector(String bean, List<String> entries) {
            this.bean = bean;
            this.entries = entries;
        }

        private String prefix() { return nested.isEmpty() ? "" : String.join(" && ", nested) + " && "; }

        @Override public void startNested(RequestPredicate predicate) { nested.addLast(predicate.toString()); }
        @Override public void endNested(RequestPredicate predicate) { nested.removeLast(); }
        @Override public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
            entries.add("FN " + bean + " " + prefix() + predicate);
        }
        @Override public void resources(Function<ServerRequest, Optional<Resource>> lookup) {
            entries.add("FN-RESOURCES " + bean + " " + prefix() + lookup.getClass().getName());
        }
        @Override public void attributes(Map<String, Object> attributes) { }
        @Override public void unknown(RouterFunction<?> function) {
            entries.add("FN-UNKNOWN " + bean + " " + prefix() + function.getClass().getName());
        }
    }
}
