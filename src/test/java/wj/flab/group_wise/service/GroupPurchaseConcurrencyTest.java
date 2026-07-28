package wj.flab.group_wise.service;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import wj.flab.group_wise.domain.product.Product;
import wj.flab.group_wise.dto.groupPurchase.request.GroupPurchaseCreateRequest;
import wj.flab.group_wise.dto.groupPurchase.request.GroupPurchaseJoinRequest;
import wj.flab.group_wise.dto.member.MemberCreateRequest;
import wj.flab.group_wise.dto.product.request.ProductStockSetRequest;
import wj.flab.group_wise.dto.product.response.ProductStockResponse;
import wj.flab.group_wise.dto.product.response.ProductViewResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import wj.flab.group_wise.repository.GroupPurchaseRepository;
import wj.flab.group_wise.service.domain.GroupPurchaseService;
import wj.flab.group_wise.service.domain.MemberService;
import wj.flab.group_wise.service.domain.ProductService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@ActiveProfiles("test")
@Slf4j
public class GroupPurchaseConcurrencyTest {

    @Autowired
    private GroupPurchaseService groupPurchaseService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductDomainDtoCreator productDomainDtoCreator;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 이 테스트는 멀티스레드로 실제 커밋을 발생시키므로(@Transactional 롤백 불가),
    // 같은 H2 인메모리 DB를 공유하는 다른 테스트가 오염되지 않도록 데이터를 직접 정리한다.
    // JPA 캐스케이드 삭제는 매핑 FK를 NULL로 갱신하려다 제약 위반이 발생하므로 테이블을 직접 비운다.
    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        List.of("group_purchase_item", "group_purchase_member", "notification", "group_purchase",
                        "product_attribute_value_stock", "product_stock", "product_attribute_value",
                        "product_attribute", "product", "member")
                .forEach(table -> jdbcTemplate.execute("TRUNCATE TABLE " + table));
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    @Test
    void 동시에_재고보다_많은_공동구매_참여요청이_들어와도_재고_수량이_음수가_되지_않고_재고수량이_0이_된_후에는_참여가_불가능해야_한다() throws InterruptedException {
        // given : 재고가 100개인 상품 추가, 공동구매 생성
        int STOCK_QUANTITY = 100;
        int THREAD_COUNT = 150;

        Long productId = setAndGetProductWithStocks(STOCK_QUANTITY);
        Long stockId = productService.getProductInfo(productId).productStocks().get(0).stockId();
        Long groupPurchaseId = setAndGetGroupPurchaseId(productId);

        // when : 동시에 150명의 사용자가 공동구매 참여 요청을 보냄 (스레드 풀은 30 정도)
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        ExecutorService executorService = Executors.newFixedThreadPool(30);
        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.await(); // 모든 스레드가 여기서 대기

                    Long memberId = setAndGetMemberId(UUID.randomUUID().toString());
                    groupPurchaseService.joinGroupPurchase(
                            groupPurchaseId,
                            memberId,
                            List.of(new GroupPurchaseJoinRequest(stockId, 1))
                    );
                    success.getAndIncrement();
                } catch (Exception e) {
                    log.error("참여 실패: {}", e.getMessage());
                    fail.getAndIncrement();
                } finally {
                    doneLatch.countDown(); // 작업이 끝날 때마다 카운트 다운
                }
            });
        }

        // then: 남은 재고는 0, 참여성공은 100, 참여 실패는 50
        readyLatch.countDown(); // 메인이 신호 -> 스레드 동시에 출발
        doneLatch.await(); // 모든 스레드가 작업을 마칠 때까지 대기
        executorService.shutdown();

        int leftStockQuantity = productService.getProductInfo(productId).productStocks()
                .stream().mapToInt(ProductStockResponse::stockQuantity).sum();
        Assertions.assertThat(leftStockQuantity).isEqualTo(0);
        Assertions.assertThat(success.get()).isEqualTo(STOCK_QUANTITY);
        Assertions.assertThat(fail.get()).isEqualTo(THREAD_COUNT - STOCK_QUANTITY);
    }

    private Long setAndGetMemberId(String email) {
        return memberService.registerMember(
                new MemberCreateRequest(
                        email,
                        "password1",
                        "address"));
    }

    private Long setAndGetGroupPurchaseId(Long productId) {
        productService.updateProductDetails(productId, productDomainDtoCreator.createUpdateProductSaleStatusRequest(Product.SaleStatus.SALE));
        Long groupPurchaseId = groupPurchaseService.createGroupPurchase(getGroupPurchaseCreateRequest(productId, "공동구매"));
        groupPurchaseService.startGroupPurchase(groupPurchaseId);
        return groupPurchaseId;
    }


    private Long setAndGetProductWithStocks(int stockCount) {
        Long productId = productService.createProduct(productDomainDtoCreator.createProductToCreate(1, 1));
//        Product product = productService.findProductById(productId); // 트랜젝션이 없어서 영속성 컨텍스트가 존재하지 않음. 따라서 productStocks를 조회할 수 없음
        ProductViewResponse productInfo = productService.getProductInfo(productId);
        Long stockId = productInfo.productStocks().get(0).stockId();

        productService.setProductStock(
                productId,
                new ProductStockSetRequest(
                        List.of(new ProductStockSetRequest.StockQuantitySetRequest(stockId, stockCount)), List.of()
                ));

        return productId;
    }

    private static GroupPurchaseCreateRequest getGroupPurchaseCreateRequest(Long productId, String title) {
        return new GroupPurchaseCreateRequest(
                title,
                productId,
                10,
                10000,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(2)
        );
    }

}
