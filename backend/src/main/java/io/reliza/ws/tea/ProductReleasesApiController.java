package io.reliza.ws.tea;

import io.reliza.model.tea.TeaDiscoveryInfo;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaPaginatedProductReleaseResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.context.request.NativeWebRequest;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-10T17:43:14.237244700-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.2.0-beta.2}")
public class ProductReleasesApiController implements ProductReleasesApi {

    private final NativeWebRequest request;

	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private TeaTransformerService teaTransformerService;
	
    @Autowired
    public ProductReleasesApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }
    
    @Override
    public ResponseEntity<TeaPaginatedProductReleaseResponse> queryTeaProductReleases(
            @Parameter(name = "pageOffset", description = "Pagination offset", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageOffset", required = false, defaultValue = "0") Long pageOffset,
            @Parameter(name = "pageSize", description = "Pagination offset", in = ParameterIn.QUERY) @Valid @RequestParam(value = "pageSize", required = false, defaultValue = "100") Long pageSize,
            @Parameter(name = "idType", description = "Type of identifier specified in the `idValue` parameter", in = ParameterIn.QUERY) @Valid @RequestParam(value = "idType", required = false) @Nullable TeaIdentifierType idType,
            @Parameter(name = "idValue", description = "If present, only the objects with the given identifier value will be returned.", in = ParameterIn.QUERY) @Valid @RequestParam(value = "idValue", required = false) @Nullable String idValue
        ) {
    	 	// TODO: implement search
    		TeaPaginatedProductReleaseResponse tpprr = new TeaPaginatedProductReleaseResponse();
    		var releaseDatas = sharedReleaseService.listProductReleasesOfOrg(UserService.USER_ORG, pageSize, pageOffset);
    		var releases = releaseDatas.stream().map(x -> teaTransformerService.transformProductReleaseToTea(x)).toList();
    		tpprr.setPageSize(pageSize);
    		tpprr.setResults(releases);
    		tpprr.setTotalResults((long) releases.size());
    		tpprr.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    		return ResponseEntity.ok(tpprr);

        }

}
