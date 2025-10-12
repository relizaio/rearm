package io.reliza.ws.tea;

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

import io.reliza.model.tea.TeaDiscoveryInfo;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.tea.TeaTransformerService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import org.springframework.web.context.request.NativeWebRequest;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Generated;

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2025-10-10T17:43:14.237244700-04:00[America/Toronto]", comments = "Generator version: 7.14.0")
@Controller
@RequestMapping("${openapi.transparencyExchange.base-path:/tea/v0.2.0-beta.2}")
public class DiscoveryApiController implements DiscoveryApi {

    private final NativeWebRequest request;

	@Autowired
	private TeaTransformerService teaTransformerService;
	
    @Autowired
    public DiscoveryApiController(NativeWebRequest request) {
        this.request = request;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public ResponseEntity<TeaDiscoveryInfo> discoveryByTei(
            @NotNull @Parameter(name = "tei", description = "Transparency Exchange Identifier (TEI) for the product being discovered. Provide the TEI as a URL-encoded string per RFC 3986, RFC 3987.", required = true, in = ParameterIn.QUERY) @Valid @RequestParam(value = "tei", required = true) String tei
        ) {
    		String decodedTEI = URLDecoder.decode(tei, StandardCharsets.UTF_8);
    		
            var otdi = teaTransformerService.performTeiDiscovery(decodedTEI);
            if (otdi.isEmpty()) {
            	TeaErrorResponse errorResponse = new TeaErrorResponse(TeaUnknownErrorType.OBJECT_UNKNOWN);
            	return (ResponseEntity<TeaDiscoveryInfo>) (ResponseEntity<?>) ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
            } else {
                return new ResponseEntity<>(otdi.get(), HttpStatus.OK);
            }

        }

}
