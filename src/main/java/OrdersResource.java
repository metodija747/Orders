import com.auth0.jwk.JwkException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
import com.kumuluz.ee.discovery.annotations.DiscoverService;
import com.kumuluz.ee.logs.cdi.Log;
import com.kumuluz.ee.logs.cdi.LogParams;
import io.opentracing.Span;
import io.opentracing.Tracer;
import org.eclipse.microprofile.faulttolerance.*;
import org.eclipse.microprofile.jwt.Claim;
import org.eclipse.microprofile.jwt.ClaimValue;
import org.eclipse.microprofile.jwt.JsonWebToken;
//import org.eclipse.microprofile.metrics.annotation.ConcurrentGauge;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.eclipse.microprofile.opentracing.Traced;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/orders")
@Log(LogParams.METRICS)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class OrdersResource {

    @Inject
    private ConfigProperties configProperties;

    @Inject
    private Tracer tracer;

    @Inject
    @Claim("cognito:groups")
    private ClaimValue<Set<String>> groups;

    @Inject
    private JsonWebToken jwt;

    @Inject
    @Claim("sub")
    private ClaimValue<Optional<String>> optSubject;

    private DynamoDbClient dynamoDB;
    private static final Logger LOGGER = Logger.getLogger(OrdersResource.class.getName());

    private volatile String currentRegion;
    private volatile String currentTableName;
    private void checkAndUpdateDynamoDbClient() {
        String newRegion = configProperties.getDynamoRegion();
        if (!newRegion.equals(currentRegion)) {
            try {
                this.dynamoDB = DynamoDbClient.builder()
                        .region(Region.of(newRegion))
                        .build();
                currentRegion = newRegion;
            } catch (Exception e) {
                LOGGER.severe("Error while creating DynamoDB client: " + e.getMessage());
                throw new WebApplicationException("Error while creating DynamoDB client: " + e.getMessage(), e, Response.Status.INTERNAL_SERVER_ERROR);
            }
        }
        currentTableName = configProperties.getTableName();
    }

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    @Inject
    @DiscoverService(value = "cart-service", environment = "dev", version = "1.0.0")
    private Optional<URL> cartServiceUrl;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Counted(name = "getOrdersCount", description = "Count of getOrders calls")
    @Timed(name = "getOrdersTime", description = "Time taken to fetch a orders")
    @Metered(name = "getOrdersMetered", description = "Rate of getOrders calls")
//    @ConcurrentGauge(name = "getOrdersConcurrent", description = "Concurrent getOrders calls")
    @Timeout(value = 20, unit = ChronoUnit.SECONDS) // Timeout after 20 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "getOrdersFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4) // Use circuit breaker after 4 failed requests
    @Bulkhead(5) // Limit concurrent calls to 5
    @Traced
    public Response getOrders(@QueryParam("page") Integer page,
                              @QueryParam("pageSize") Integer pageSize) {

        // Default values for page and pageSize if they are not provided
        if (page == null) {
            page = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }

        if (jwt == null) {
            LOGGER.info("Unauthorized: only authenticated users can view his/hers cart.");
            return Response.ok("Unauthorized: only authenticated users can view his/hers cart.").build();
        }
        String userId = optSubject.getValue().orElse("default_value");

        Span span = tracer.buildSpan("getOrders").start();
        span.setTag("userId", userId);
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("event", "getOrders");
        logMap.put("value", userId);
        logMap.put("groups", groups.getValue());
        logMap.put("email", jwt.getClaim("email"));
        span.log(logMap);
        LOGGER.info("getOrders method called");
        checkAndUpdateDynamoDbClient();

        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":v_userId", AttributeValue.builder().s(userId).build());

        Map<String, String> expressionAttributeNames = new HashMap<>();
        expressionAttributeNames.put("#N", "Name");
        expressionAttributeNames.put("#T", "TimeStamp");

        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(currentTableName)
                .keyConditionExpression("UserId = :v_userId")
                .expressionAttributeValues(expressionAttributeValues)
                .projectionExpression("#N, Surname, #T, TotalPrice, OrderStatus, OrderList, Email, Address, TelNumber")
                .expressionAttributeNames(expressionAttributeNames)
                .build();
        QueryResponse queryResponse = dynamoDB.query(queryRequest);
        try {

            List<Map<String, AttributeValue>> items = queryResponse.items();
            LOGGER.info("Looks like:" + items);
            int totalPages = (int) Math.ceil((double) items.size() / pageSize);

            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, items.size());
            List<Map<String, AttributeValue>> pagedItems = items.subList(start, end);

            List<Map<String, String>> orders = new ArrayList<>();
            for (Map<String, AttributeValue> item : pagedItems) {
                orders.add(ResponseTransformer.transformOrderItem(item));
            }

            orders.forEach(order -> {
                Instant timestamp = Instant.parse(order.get("TimeStamp"));
                String formattedTimestamp = formatter.format(timestamp.atZone(ZoneId.systemDefault()).toLocalDate());
                order.put("TimeStamp", formattedTimestamp);
            });

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("orders", orders);
            responseBody.put("totalPages", totalPages);
            span.setTag("completed", true);

            return Response.ok().entity(new Gson().toJson(responseBody)).build();

        } catch (DynamoDbException e) {
            LOGGER.warning("Error while getting orders" + e);
            span.setTag("error", true);
            throw new WebApplicationException("Error while getting orders. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            span.finish();
        }
    }
    public Response getOrdersFallback(@QueryParam("page") Integer page,
                                      @QueryParam("pageSize") Integer pageSize) {
        LOGGER.info("Fallback activated: Unable to fetch orders at the moment for token: " + optSubject.getValue().orElse("default_value"));
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to fetch orders at the moment. Please try again later.");
        return Response.ok(response).build();
    }


    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Counted(name = "addOrderCount", description = "Count of addOrder calls")
    @Timed(name = "addOrderTime", description = "Time taken to add a order")
    @Metered(name = "addOrderMetered", description = "Rate of addOrder calls")
    @Timeout(value = 20, unit = ChronoUnit.SECONDS) // Timeout after 20 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "addOrderFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4) // Use circuit breaker   after 4 failed requests
    @Bulkhead(6) // Lim it concurrent c all s to 5
    @Traced
    public Response checkoutOrder(Order order) {
        // Parse the token f rom t he Authorization header

        if (jwt == null) {
            LOGGER.info("Unauthorized: only authenticated users can add products to his/hers cart.");
            return Response.status(Response.Status.UNAUTHORIZED).entity("Unauthorized: only authenticated users can add products to his/hers cart.").build();
        }
        String userId = optSubject.getValue().orElse("default_value");

        Span span = tracer.buildSpan("addOrder").start();
        span.setTag("userId", userId);
        Map<String, Object> logMap = new HashMap<>();
        logMap.put("event", "addToCart");
        logMap.put("value", userId);
        logMap.put("groups", groups.getValue());
        logMap.put("email", jwt.getClaim("email"));
        span.log(logMap);
        LOGGER.info("addOrder method called");
        checkAndUpdateDynamoDbClient();


        String hashKeyInput = userId + order.getOrderListStr() + Instant.now().toString();
        String hashKey = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(hashKeyInput.getBytes(StandardCharsets.UTF_8));
            hashKey = Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        try {
            String timeStamp = Instant.now().toString();

            Map<String, AttributeValue> itemValues = new HashMap<>();
            itemValues.put("UserId", AttributeValue.builder().s(userId).build());
            itemValues.put("HashKey", AttributeValue.builder().s(hashKey).build());
            itemValues.put("Email", AttributeValue.builder().s(order.getEmail()).build());
            itemValues.put("Name", AttributeValue.builder().s(order.getName()).build());
            itemValues.put("Surname", AttributeValue.builder().s(order.getSurname()).build());
            itemValues.put("Address", AttributeValue.builder().s(order.getAddress()).build());
            itemValues.put("TelNumber", AttributeValue.builder().s(order.getTelNumber()).build());
            itemValues.put("OrderList", AttributeValue.builder().s(order.getOrderListStr()).build());
            itemValues.put("TotalPrice", AttributeValue.builder().n(order.getTotalPrice().toString()).build());
            itemValues.put("OrderStatus", AttributeValue.builder().s("COMPLETED").build());
            itemValues.put("TimeStamp", AttributeValue.builder().s(timeStamp).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(currentTableName)
                    .item(itemValues)
                    .build();

            dynamoDB.putItem(putItemRequest);

            Gson gson = new Gson();
            if (cartServiceUrl.isPresent()) {
                CartServiceApi api = RestClientBuilder.newBuilder()
                        .baseUrl(new URL(cartServiceUrl.get().toString()))
                        .build(CartServiceApi.class);

                Response response = api.deleteCart();

                if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                    throw new RuntimeException("Failed : HTTP error code : " + response.getStatus());
                }
            }
            span.setTag("completed", true);
            return Response.ok("Order processed successfully.").build();
        } catch (DynamoDbException | MalformedURLException e) {
            LOGGER.log(Level.SEVERE, "Error while adding order for user " + userId, e);
            span.setTag("error", true);
            throw new WebApplicationException("Error while adding order. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            span.finish();
        }
    }
    public Response addOrderFallback(Order order) {
        LOGGER.info("Fallback activated: Unable to add order at the moment for token: " + optSubject.getValue().orElse("default_value"));
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to add order at the moment. Please try again later.");
        return Response.ok(response).build();
    }
}
