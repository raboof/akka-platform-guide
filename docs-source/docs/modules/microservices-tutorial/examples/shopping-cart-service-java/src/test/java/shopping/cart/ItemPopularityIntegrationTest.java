package shopping.cart;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorSystem;
import akka.cluster.MemberStatus;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.typed.Cluster;
import akka.testkit.SocketUtil;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import shopping.cart.repository.ItemPopularityRepository;
import shopping.cart.repository.SpringIntegration;

public class ItemPopularityIntegrationTest {

  private static final String SERVICE_NAME = "shopping-cart-service";

  private static Config config() {
    int grpcPort = SocketUtil.temporaryServerAddress("127.0.0.1", false).getPort();
    int managementPort = SocketUtil.temporaryServerAddress("127.0.0.1", false).getPort();

    Config dynamicConfig =
        DynamicTestConfig.endpointConfig(SERVICE_NAME, grpcPort)
            .withFallback(DynamicTestConfig.clusterConfig(SERVICE_NAME, managementPort));
    return dynamicConfig
        .withFallback(ConfigFactory.parseResources("persistence-test.conf"))
        .withFallback(ConfigFactory.load("application.conf"))
        .resolve();
  }

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config());

  private static ActorSystem<?> system = testKit.system();
  private static ItemPopularityRepository itemPopularityRepository;

  @BeforeClass
  public static void beforeClass() throws Exception {

    ApplicationContext springContext = SpringIntegration.applicationContext(system);
    itemPopularityRepository = springContext.getBean(ItemPopularityRepository.class);
    JpaTransactionManager transactionManager = springContext.getBean(JpaTransactionManager.class);
    // create DB schema and tables
    CreateTableTestUtils.createTables(transactionManager, system);

    // Start the sharded persistent entities
    ShoppingCart.init(system);

    // Start the projection under test
    ItemPopularityProjection.init(system, transactionManager, itemPopularityRepository);

    // Await until the cluster has formed
    TestProbe<Object> probe = testKit.createTestProbe();
    Cluster node = Cluster.get(system);
    probe.awaitAssert(
        () -> {
          assertEquals(MemberStatus.up(), node.selfMember().status());
          return null;
        });
  }

  @Test
  public void consumeCartEventsAndUpdatePopularityCount() throws Exception {
    ClusterSharding sharding = ClusterSharding.get(system);

    final String cartId1 = "cart1";
    final String cartId2 = "cart2";
    final String item1 = "item1";
    final String item2 = "item2";

    EntityRef<ShoppingCart.Command> cart1 = sharding.entityRefFor(ShoppingCart.ENTITY_KEY, cartId1);
    EntityRef<ShoppingCart.Command> cart2 = sharding.entityRefFor(ShoppingCart.ENTITY_KEY, cartId2);

    final Duration timeout = Duration.ofSeconds(3);

    CompletionStage<ShoppingCart.Summary> reply1 =
        cart1.askWithStatus(replyTo -> new ShoppingCart.AddItem(item1, 3, replyTo), timeout);
    ShoppingCart.Summary summary1 = reply1.toCompletableFuture().get(3, SECONDS);
    assertEquals(3, summary1.items.get(item1).intValue());

    TestProbe<Object> probe = testKit.createTestProbe();
    probe.awaitAssert(
        () -> {
          Optional<ItemPopularity> item1Popularity = itemPopularityRepository.findById(item1);
          assertTrue(item1Popularity.isPresent());
          assertEquals(3L, item1Popularity.get().getCount());
          return null;
        });

    CompletionStage<ShoppingCart.Summary> reply2 =
        cart1.askWithStatus(replyTo -> new ShoppingCart.AddItem(item2, 5, replyTo), timeout);
    ShoppingCart.Summary summary2 = reply2.toCompletableFuture().get(3, SECONDS);
    assertEquals(2, summary2.items.size());
    assertEquals(5, summary2.items.get(item2).intValue());
    // another cart
    CompletionStage<ShoppingCart.Summary> reply3 =
        cart2.askWithStatus(replyTo -> new ShoppingCart.AddItem(item2, 4, replyTo), timeout);
    ShoppingCart.Summary summary3 = reply3.toCompletableFuture().get(3, SECONDS);
    assertEquals(1, summary3.items.size());
    assertEquals(4L, summary3.items.get(item2).intValue());

    probe.awaitAssert(
        Duration.ofSeconds(10),
        () -> {
          Optional<ItemPopularity> item2Popularity = itemPopularityRepository.findById(item2);
          assertTrue(item2Popularity.isPresent());
          assertEquals(5 + 4, item2Popularity.get().getCount());
          return null;
        });
  }

  @Test
  public void safelyUpdatePopularityCount() throws Exception {
    ClusterSharding sharding = ClusterSharding.get(system);

    final String item = "concurrent-item";
    int cartCount = 100;
    int itemCount = 1;
    final Duration timeout = Duration.ofSeconds(30);

    // Given `item1` is already on the popularity projection DB...
    CompletionStage<ShoppingCart.Summary> rep1 =
        sharding
            .entityRefFor(ShoppingCart.ENTITY_KEY, "concurrent-cart0")
            .askWithStatus(replyTo -> new ShoppingCart.AddItem(item, itemCount, replyTo), timeout);

    TestProbe<Object> probe = testKit.createTestProbe();
    probe.awaitAssert(
        () -> {
          Optional<ItemPopularity> item1Popularity = itemPopularityRepository.findById(item);
          assertTrue(item1Popularity.isPresent());
          assertEquals(itemCount, item1Popularity.get().getCount());
          return null;
        });

    // ... when 99 concurrent carts add `item1`...
    for (int i = 1; i < cartCount; i++) {
      sharding
          .entityRefFor(ShoppingCart.ENTITY_KEY, "concurrent-cart" + i)
          .<ShoppingCart.Summary>askWithStatus(
              replyTo -> new ShoppingCart.AddItem(item, itemCount, replyTo), timeout);
    }

    // ... then the popularity count is 100
    probe.awaitAssert(
        timeout,
        () -> {
          Optional<ItemPopularity> item1Popularity = itemPopularityRepository.findById(item);
          assertTrue(item1Popularity.isPresent());
          assertEquals(cartCount * itemCount, item1Popularity.get().getCount());
          return null;
        });
  }
}
