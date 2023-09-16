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
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Metered;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameters;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
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
@SecurityRequirement(name = "jwtAuth")
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
    @Operation(
            summary = "Fetch orders for the user",
            description = "Fetches the list of orders placed by the authenticated user with pagination support."
    )
    @APIResponses(value = {
            @APIResponse(
                    responseCode = "200",
                    description = "Orders successfully fetched",
                    content = @Content(
                            mediaType = "application/json"
                    )
            ),
            @APIResponse(
                    responseCode = "401",
                    description = "Unauthorized, invalid token",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = Error.class)
                    )
            ),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal server error",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = Error.class)
                    )
            )
    })
    @Parameters(value = {
            @Parameter(
                    name = "page",
                    description = "The page number",
                    required = false,
                    schema = @Schema(type = SchemaType.INTEGER, defaultValue = "1")
            ),
            @Parameter(
                    name = "pageSize",
                    description = "The number of items per page",
                    required = false,
                    schema = @Schema(type = SchemaType.INTEGER, defaultValue = "10")
            )
    })
    @Produces(MediaType.APPLICATION_JSON)
    @Counted(name = "getOrdersCount", description = "Count of getOrders calls")
    @Timed(name = "getOrdersTime", description = "Time taken to fetch a orders")
    @Metered(name = "getOrdersMetered", description = "Rate of getOrders calls")
    @Timeout(value = 50, unit = ChronoUnit.SECONDS) // Timeout after 50 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "getOrdersFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 2000)
    @Bulkhead(100) // Limit concurrent calls to 100
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
            LOGGER.log(Level.SEVERE, "Token verification failed");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent")
                    .entity("Invalid token.")
                    .build();
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
            LOGGER.log(Level.INFO, "User's orders obtained successfully");
            return Response.ok()
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent")
                    .entity(new Gson().toJson(responseBody))
                    .build();
        } catch (DynamoDbException e) {
            LOGGER.log(Level.INFO, "Failed to obtain user's orders", e);
            span.setTag("error", true);
            throw new WebApplicationException("Failed to obtain user's orders. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        }
        catch (Exception e){
            LOGGER.log(Level.INFO, "Failed to obtain user's orders", e);
            throw new WebApplicationException("Failed to obtain user's orders. Please try again later.", e, Response.Status.INTERNAL_SERVER_ERROR);
        }

        finally {
            span.finish();
        }
    }
    public Response getOrdersFallback(@QueryParam("page") Integer page,
                                      @QueryParam("pageSize") Integer pageSize) {
        LOGGER.info("Fallback activated: Unable to fetch orders at the moment for token: " + optSubject.getValue().orElse("default_value"));
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to fetch orders at the moment. Please try again later.");
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent")
                .entity(new Gson().toJson(response))
                .build();
    }



    @POST
    @Operation(summary = "Perform checkout of a cart",
            description = "Processes the order and returns payment confirmation.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Payment successful"),
            @APIResponse(responseCode = "401", description = "Unauthorized access, Invalid token"),
            @APIResponse(responseCode = "500", description = "Internal Server Error")
    })
    @RequestBody(
            description = "Order object that needs to be processed",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                            implementation = Order.class,
                            example = "{ \"email\": \"newemail@example.com\", \"address\": \"123 New St, New City, New State\", \"totalPrice\": \"299.99\", \"orderListStr\": \"[{\\\"quantity\\\":\\\"3\\\",\\\"productName\\\":\\\"New Product 1\\\"},{\\\"quantity\\\":\\\"2\\\",\\\"productName\\\":\\\"New Product 2\\\"},{\\\"quantity\\\":\\\"1\\\",\\\"productName\\\":\\\"New Product 3\\\"}]\", \"telNumber\": \"666777888\", \"surname\": \"NewSurname\", \"name\": \"NewName\" }"
                    )
            )
    )
    @Consumes(MediaType.APPLICATION_JSON)
    @Counted(name = "addOrderCount", description = "Count of addOrder calls")
    @Timed(name = "addOrderTime", description = "Time taken to add a order")
    @Metered(name = "addOrderMetered", description = "Rate of addOrder calls")
    @Timeout(value = 50, unit = ChronoUnit.SECONDS) // Timeout after 50 seconds
    @Retry(maxRetries = 3) // Retry up to 3 times
    @Fallback(fallbackMethod = "addOrderFallback") // Fallback method if all retries fail
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 2000)
    @Bulkhead(100) // Lim it concurrent c all s to 100
    @Traced
    public Response checkoutOrder(Order order) {

        if (jwt == null) {
            LOGGER.log(Level.SEVERE, "Token verification failed");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent")
                    .entity("Invalid token.")
                    .build();
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
                String authHeader = "Bearer " + jwt.getRawToken();
                Response response = api.deleteCart(authHeader);

                if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                    throw new RuntimeException("Failed : HTTP error code : " + response.getStatus());
                }
            }
            LOGGER.info("Payment successful");
            span.setTag("completed", true);
            return Response.status(Response.Status.OK)
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    .header("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent")
                    .entity(new Gson().toJson("Payment successful"))
                    .build();
        } catch (DynamoDbException | MalformedURLException e) {
            LOGGER.log(Level.SEVERE, "Failed to process checkout", e);
            span.setTag("error", true);
            throw new WebApplicationException("Failed to process checkout", e, Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            span.finish();
        }
    }
    public Response addOrderFallback(Order order) {
        LOGGER.info("Fallback activated: Unable to process checkout at the moment.");
        Map<String, String> response = new HashMap<>();
        response.put("description", "Unable to process checkout at the moment. Please try again later.");
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token,X-Amz-User-Agent")
                .entity(new Gson().toJson(response))
                .build();
    }

}
