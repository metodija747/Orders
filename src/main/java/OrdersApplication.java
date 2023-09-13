import com.kumuluz.ee.discovery.annotations.RegisterService;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.logging.Logger;

@ApplicationPath("/")
@RegisterService
@OpenAPIDefinition(info = @Info(title = "Orders Catalog API", version = "1.0.0"))
public class OrdersApplication extends Application {
    private static final Logger LOG = Logger.getLogger(OrdersApplication.class.getName());
    public OrdersApplication() {
        LOG.info("OrdersApplication started!");
    }
}
