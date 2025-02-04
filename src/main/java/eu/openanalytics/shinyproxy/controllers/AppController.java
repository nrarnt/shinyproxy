/**
 * ShinyProxy
 *
 * Copyright (C) 2016-2023 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxy.controllers;

import com.fasterxml.jackson.annotation.JsonView;
import eu.openanalytics.containerproxy.api.dto.ApiResponse;
import eu.openanalytics.containerproxy.api.dto.ChangeProxyStatusDto;
import eu.openanalytics.containerproxy.api.dto.SwaggerDto;
import eu.openanalytics.containerproxy.model.Views;
import eu.openanalytics.containerproxy.model.runtime.AllowedParametersForUser;
import eu.openanalytics.containerproxy.model.runtime.ParameterValues;
import eu.openanalytics.containerproxy.model.runtime.Proxy;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.DisplayNameKey;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.ParameterValuesKey;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.RuntimeValue;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.service.AsyncProxyService;
import eu.openanalytics.containerproxy.service.InvalidParametersException;
import eu.openanalytics.containerproxy.service.ParametersService;
import eu.openanalytics.containerproxy.util.ContextPathHelper;
import eu.openanalytics.containerproxy.util.ProxyMappingManager;
import eu.openanalytics.shinyproxy.AppRequestInfo;
import eu.openanalytics.shinyproxy.ShinyProxyIframeScriptInjector;
import eu.openanalytics.shinyproxy.controllers.dto.ShinyProxyApiResponse;
import eu.openanalytics.shinyproxy.runtimevalues.AppInstanceKey;
import eu.openanalytics.shinyproxy.runtimevalues.PublicPathKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.ExpressionContext;
import org.thymeleaf.spring5.dialect.SpringStandardDialect;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import javax.inject.Inject;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Controller
public class AppController extends BaseController {

	@Inject
	private ProxyMappingManager mappingManager;

	@Inject
	private AsyncProxyService asyncProxyService;

    @Inject
    private ParametersService parameterService;

	@RequestMapping(value={"/app_i/*/**", "/app/**"}, method=RequestMethod.GET)
	public ModelAndView app(ModelMap map, HttpServletRequest request, HttpServletResponse response) {
		AppRequestInfo appRequestInfo = AppRequestInfo.fromRequestOrNull(request);
		if (appRequestInfo == null) {
			request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.FORBIDDEN.value());
			return new ModelAndView("forward:/error");
		}

		Proxy proxy = findUserProxy(appRequestInfo);

		ProxySpec spec = proxyService.getProxySpec(appRequestInfo.getAppName());
		Optional<RedirectView> redirect = createRedirectIfRequired(request, appRequestInfo, proxy, spec);
		if (redirect.isPresent()) {
			return new ModelAndView(redirect.get());
		}

		if (proxy == null && spec == null) {
			request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.FORBIDDEN.value());
			return new ModelAndView("forward:/error");
		}

		prepareMap(map, request);
		map.put("heartbeatRate", getHeartbeatRate());
		map.put("page", "app");
		map.put("appName", appRequestInfo.getAppName());
		map.put("appInstance", appRequestInfo.getAppInstance());
		map.put("appInstanceDisplayName", appRequestInfo.getAppInstanceDisplayName());
		map.put("appPath", appRequestInfo.getAppPath());
		map.put("containerSubPath", buildContainerSubPath(request, appRequestInfo));
		ParameterValues previousParameters = null;
		if (proxy == null || proxy.getRuntimeObjectOrNull(DisplayNameKey.inst) == null) {
			if (spec.getDisplayName() == null || spec.getDisplayName().isEmpty()) {
				map.put("appTitle", spec.getId());
			} else {
				map.put("appTitle", spec.getDisplayName());
			}
			map.put("proxy", null);
		} else {
			map.put("appTitle", proxy.getRuntimeValue(DisplayNameKey.inst));
			previousParameters = proxy.getRuntimeObjectOrNull(ParameterValuesKey.inst);
		}
		map.put("proxy", proxy);
		if (spec != null && spec.getParameters() != null) {
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			AllowedParametersForUser allowedParametersForUser = parameterService.calculateAllowedParametersForUser(auth, spec, previousParameters);
			map.put("parameterAllowedCombinations", allowedParametersForUser.getAllowedCombinations());
			map.put("parameterValues", allowedParametersForUser.getValues());
			map.put("parameterDefaults", allowedParametersForUser.getDefaultValue());
			map.put("parameterDefinitions", spec.getParameters().getDefinitions());
			map.put("parameterIds", spec.getParameters().getIds());

			if (spec.getParameters().getTemplate() != null) {
				map.put("parameterFragment", renderParameterTemplate(spec.getParameters().getTemplate(), map));
			} else {
				map.put("parameterFragment", null);
			}
		} else {
			map.put("parameterValues", null);
			map.put("parameterDefaults", null);
			map.put("parameterDefinitions", null);
			map.put("parameterIds", null);
			map.put("parameterFragment", null);
		}

		return new ModelAndView("app", map);
	}

	@Operation(summary = "Start an app.", tags = "ShinyProxy",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ChangeProxyStatusDto.class),
							examples = {
									@ExampleObject(name = "With parameters", value = "{\"parameters\":{\"resources\":\"2 CPU cores - 8G RAM\",\"other_parameter\":\"example\"}}")
							}
					)
			)
	)
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "The proxy has been created.",
					content = {
							@Content(
									mediaType = "application/json",
									schema = @Schema(implementation = SwaggerDto.ProxyResponse.class),
									examples = {
											@ExampleObject(value = "{\"status\":\"success\",\"data\":{\"id\":\"cdaa8056-4f96-428e-91e8-bc13518d8987\",\"status\":\"New\",\"startupTimestamp\":0,\"createdTimestamp\":1671707875757," +
													"\"userId\":\"jack\",\"specId\":\"01_hello\",\"displayName\":\"Hello Application\",\"containers\":[],\"runtimeValues\":{\"SHINYPROXY_FORCE_FULL_RELOAD\":false," +
													"\"SHINYPROXY_WEBSOCKET_RECONNECTION_MODE\":\"None\",\"SHINYPROXY_MAX_INSTANCES\":100,\"SHINYPROXY_PUBLIC_PATH\":\"/app_proxy/cdaa8056-4f96-428e-91e8-bc13518d8987/\"," +
													"\"SHINYPROXY_APP_INSTANCE\":\"default\"}}}\n")
									}
							)
					}),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "400",
					description = "Invalid request, app not started.",
					content = {
							@Content(
									mediaType = "application/json",
									examples = {
											@ExampleObject(name = "Max instances reached", value = "{\"status\":\"fail\",\"data\":\"Cannot start new proxy because the maximum amount of instances of this proxy has been reached\"}"),
											@ExampleObject(name = "Instance already exists", value = "{\"status\":\"fail\",\"data\":\"You already have an instance of this app with the given name\"}"),
											@ExampleObject(name = "Parameters required", value = "{\"status\":\"fail\",\"data\":\"No parameters provided, but proxy spec expects parameters\"}"),
											@ExampleObject(name = "Missing parameter", value = "{\"status\":\"fail\",\"data\":\"Missing value for parameter example\"}"),
											@ExampleObject(name = "Invalid parameter value", value = "{\"status\":\"fail\",\"data\":\"Provided parameter values are not allowed\"}")
									}
							)
					}),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "403",
					description = "Proxy spec not found or no permission to use this proxy spec.",
					content = {
							@Content(
									mediaType = "application/json",
									examples = {@ExampleObject(value = "{\"status\": \"fail\", \"data\": \"forbidden\"}")}
							)
					}),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "500",
					description = "Failed to start proxy.",
					content = {
							@Content(
									mediaType = "application/json",
									examples = {@ExampleObject(value = "{\"status\": \"fail\", \"data\": \"Failed to start proxy\"}")}
							)
					}),
	})
	@ResponseBody
	@JsonView(Views.UserApi.class)
	@RequestMapping(value = "/app_i/{specId}/{appInstanceName}", method = RequestMethod.POST)
	public ResponseEntity<ApiResponse<Proxy>> startApp(@PathVariable String specId, @PathVariable String appInstanceName, @RequestBody(required = false) AppBody appBody) {
		ProxySpec spec = proxyService.getProxySpec(specId);
		if (!userService.canAccess(spec)) {
			return ApiResponse.failForbidden();
		}
		Proxy proxy = findUserProxy(specId, appInstanceName);
		if (proxy != null) {
			return ApiResponse.fail("You already have an instance of this app with the given name");
		}

		if (!validateProxyStart(spec)) {
			return ApiResponse.fail("Cannot start new app because the maximum amount of instances of this app has been reached");
		}

		List<RuntimeValue> runtimeValues = shinyProxySpecProvider.getRuntimeValues(spec);
		String id = UUID.randomUUID().toString();
		runtimeValues.add(new RuntimeValue(PublicPathKey.inst, getPublicPath(id)));
		runtimeValues.add(new RuntimeValue(AppInstanceKey.inst, appInstanceName));

		try {
			return ApiResponse.success(asyncProxyService.startProxy(spec, runtimeValues, id, (appBody != null) ? appBody.getParameters() : null));
		} catch (InvalidParametersException ex) {
			return ApiResponse.fail(ex.getMessage());
		} catch (Throwable t ) {
			return ApiResponse.error("Failed to start proxy");
		}
	}

	@Operation(summary = "Proxy request to app. This endpoint is used to serve the iframe, hence it makes some assumptions. Do not use it directly or for embedding.", tags = "ShinyProxy")
	@ApiResponses(value = {
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "401",
					description = "User is not authenticated.",
					content = {
							@Content(
									mediaType = "application/json",
									examples = {
											@ExampleObject(value = "{\"message\":\"shinyproxy_authentication_required\",\"status\":\"fail\"}")
									}
							)
					}),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "410",
					description = "App has been stopped or the app never existed or the user has no access to the app.",
					content = {
							@Content(
									mediaType = "application/json",
									examples = {
											@ExampleObject(value = "{\"message\":\"app_stopped_or_non_existent\",\"status\":\"fail\"}")
									}
							)
					}),
	})
	@RequestMapping(value={"/app_proxy/{proxyId}/**"})
	public void appProxy(@PathVariable String proxyId, HttpServletRequest request, HttpServletResponse response) throws IOException {
		String requestUrl = request.getRequestURI().substring(getBasePublicPath().length());

		Proxy proxy = proxyService.getProxy(proxyId);
		if (proxy == null || proxy.getStatus().isUnavailable() || !userService.isOwner(proxy)) {
			ShinyProxyApiResponse.appStoppedOrNonExistent(response);
			return;
		}
		try {
			mappingManager.dispatchAsync(proxy.getId(), requestUrl, request, response);
		} catch (Exception e) {
			throw new RuntimeException("Error routing proxy request", e);
		}
	}

	/**
	 * Special handler for HTML requests that inject the ShinyProxy iframe javascript.
	 */
	@RequestMapping(value={"/app_proxy/{proxyId}/**"}, produces= "text/html")
	public void appProxyHtml(@PathVariable String proxyId, HttpServletRequest request, HttpServletResponse response) throws IOException {
		String requestUrl = request.getRequestURI().substring(getBasePublicPath().length());

		Proxy proxy = proxyService.getProxy(proxyId);
		if (proxy == null || proxy.getStatus().isUnavailable() || !userService.isOwner(proxy)) {
			ShinyProxyApiResponse.appStoppedOrNonExistent(response);
			return;
		}
		try {
			mappingManager.dispatchAsync(proxyId, requestUrl, request, response, (exchange) -> {
				exchange.getRequestHeaders().remove("Accept-Encoding"); // ensure no encoding is used
				exchange.addResponseWrapper((factory, exchange1) -> new ShinyProxyIframeScriptInjector(factory.create(), exchange1));
			});
		} catch (Exception e) {
			throw new RuntimeException("Error routing proxy request", e);
		}
	}

	private String buildContainerSubPath(HttpServletRequest request, AppRequestInfo appRequestInfo) {
		String queryString = ServletUriComponentsBuilder.fromRequest(request)
				.replaceQueryParam("sp_hide_navbar")
				.replaceQueryParam("sp_instance_override")
				.build().getQuery();

		String res = UriComponentsBuilder
				.fromPath(appRequestInfo.getSubPath())
				.query(queryString)
				.build(true)
				.toUriString();

		if (res.startsWith("/")) {
			return res.substring(1);
		}
		return res;
	}

	private String getPublicPath(String proxyId) {
		return getBasePublicPath() + proxyId + "/";
	}

	private String getBasePublicPath() {
		return ContextPathHelper.withEndingSlash() + "app_proxy/";
	}

	private Optional<RedirectView> createRedirectIfRequired(HttpServletRequest request, AppRequestInfo appRequestInfo, Proxy proxy, ProxySpec spec) {
		// if sub-path is empty -> no ending slash -> no ending slash and redirect required
		if (appRequestInfo.getSubPath() == null || appRequestInfo.getSubPath().equals("/")) {
			return Optional.empty();
		}

		if (proxy == null) {
			// sub-path is non-empty, but proxy does not yet exist -> redirect to root path
			String uri = ServletUriComponentsBuilder.fromRequest(request)
					.replacePath(appRequestInfo.getAppPath())
					.query(null)
					.build()
					.toUriString();
			return Optional.of(new RedirectView(uri));
		}

		// if sub-path is just the mapping -> no ending slash and redirect required
		String mapping = appRequestInfo.getSubPath().substring(1);
		if (mapping.contains("/")) {
			return Optional.empty();
		}

		boolean mappingWithoutSlash = spec.getContainerSpecs().get(0)
				.getPortMapping()
				.stream()
				.anyMatch(it -> it.getName().equals(mapping));
		if (mappingWithoutSlash) {
			String uri = ServletUriComponentsBuilder.fromRequest(request)
					.path("/")
					.build()
					.toUriString();
			return Optional.of(new RedirectView(uri));
		}

		return Optional.empty();
	}

	private String renderParameterTemplate(String template, ModelMap map) {
		TemplateEngine templateEngine = new TemplateEngine();
		StringTemplateResolver stringTemplateResolver = new StringTemplateResolver();
		stringTemplateResolver.setTemplateMode(TemplateMode.HTML);
		stringTemplateResolver.setCacheable(false);

		templateEngine.setTemplateResolver(stringTemplateResolver);
		templateEngine.setDialect(new SpringStandardDialect());

		ExpressionContext context = new ExpressionContext(templateEngine.getConfiguration(), null, map);
		return templateEngine.process(template, context);
	}

    private static class AppBody {
        private Map<String, String> parameters;

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters;
        }
    }

}
