/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.NativeWebRequest;

import io.reliza.model.tea.TeaDiscoveryInfo;
import io.reliza.model.tea.TeaServerInfo;
import io.reliza.service.tea.TeaSurfaceService;
import io.reliza.service.tea.TeaSurfaceService.Decision;
import io.reliza.service.tea.TeaSurfaceService.Head;
import io.reliza.service.tea.TeaTei;
import io.reliza.ws.tea.TeaRequests.TeaRequestContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Discovery by TEI or by PURL (task TEA-6): the published product releases of the organization
 * whose base is called, newest first. The parameters arrive query-decoded once by Spring and are
 * used as they are; decoding them again would eat a literal percent sign of a purl.
 */
@Slf4j
@Controller
@RequestMapping(TeaPaths.ORG_BASE)
public class DiscoveryApiController implements DiscoveryApi {

	@Autowired private TeaRequests teaRequests;
	@Autowired private TeaSurfaceService teaSurfaceService;

	private final NativeWebRequest request;

	@Autowired
	public DiscoveryApiController(NativeWebRequest request) {
		this.request = request;
	}

	@Override
	public Optional<NativeWebRequest> getRequest() {
		return Optional.ofNullable(request);
	}

	@Override
	public ResponseEntity<List<TeaDiscoveryInfo>> discover(String tei, String purl) {
		TeaRequestContext ctx = teaRequests.context(request);
		boolean hasTei = null != tei;
		boolean hasPurl = null != purl;
		if (hasTei == hasPurl) throw TeaRefusal.invalidRequest("exactly one of tei and purl is required");
		Decision<List<Head>> d;
		if (hasTei) {
			if (StringUtils.isBlank(tei)) throw TeaRefusal.invalidRequest("tei is empty");
			String problem = TeaTei.problem(tei);
			if (null != problem) throw TeaRefusal.invalidRequest(problem);
			TeaTei parsed = TeaTei.parse(tei).orElseThrow();
			String idProblem = parsed.identifierProblem();
			if (null != idProblem) throw TeaRefusal.invalidRequest(idProblem);
			d = teaSurfaceService.discover(ctx.caller(), ctx.org(), parsed);
		} else {
			if (StringUtils.isBlank(purl)) throw TeaRefusal.invalidRequest("purl is empty");
			d = teaSurfaceService.discoverByPurl(ctx.caller(), ctx.org(), purl);
		}
		List<Head> served = TeaRendering.served(d);
		List<TeaDiscoveryInfo> out = new ArrayList<>();
		for (Head h : served) {
			TeaServerInfo server = new TeaServerInfo(URI.create(ctx.org().apiBase()), new ArrayList<>(List.of(TeaPaths.API_VERSION)));
			server.setPriority(1.0f);
			out.add(new TeaDiscoveryInfo(h.publication().getUuid(), new ArrayList<>(List.of(server))));
		}
		log.debug("TEA discovery of organization {}: caller {}, {} results", ctx.org().org(), ctx.caller().state(), out.size());
		return ResponseEntity.ok().headers(TeaResponses.headersFor(ctx)).body(out);
	}
}
