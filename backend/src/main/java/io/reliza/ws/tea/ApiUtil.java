/**
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech) (7.13.0).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */
package io.reliza.ws.tea;

import org.springframework.web.context.request.NativeWebRequest;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class ApiUtil {
    public static void setExampleResponse(NativeWebRequest req, String contentType, String example) {
        try {
            HttpServletResponse res = req.getNativeResponse(HttpServletResponse.class);
            res.setCharacterEncoding("UTF-8");
            res.addHeader("Content-Type", contentType);
            res.getWriter().print(example);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
