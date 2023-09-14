import com.kumuluz.ee.discovery.annotations.RegisterService;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.logging.Logger;

@ApplicationPath("/")
@RegisterService
@OpenAPIDefinition(
        info = @Info(title = "Orders  Catalog API", version = "1.0.0"),
        security = @SecurityRequirement(name = "jwtAuth")
)
@SecurityScheme(
        securitySchemeName = "jwtAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OrdersApplication extends Application {
    private static final Logger LOG = Logger.getLogger(OrdersApplication.class.getName());
    public OrdersApplication() {
        LOG.info("OrdersApplication started!");
    }
}
