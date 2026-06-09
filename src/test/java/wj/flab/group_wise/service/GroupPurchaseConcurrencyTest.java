package wj.flab.group_wise.service;

import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
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

        ExecutorService executorService = Executors.newFixedThreadPool(30);
        executorService.submit(() -> {
            try {
                readyLatch.await(); // 모든 스레드가 준비될 때까지 대기
                for (int i = 0; i < THREAD_COUNT; i++) {
                    new Thread(() -> {
                        try {
                            Long memberId = setAndGetMemberId(UUID.randomUUID().toString());
                            groupPurchaseService.joinGroupPurchase(
                                    groupPurchaseId,
                                    memberId,
                                    List.of(new GroupPurchaseJoinRequest(stockId, 1))
                            );
                        } catch (Exception e) {
                            log.error("참여 실패: {}", e.getMessage());
                        } finally {
                            doneLatch.countDown(); // 작업이 끝날 때마다 카운트 다운
                        }
                    }).start();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // then:
        readyLatch.countDown();
        doneLatch.await(); // 모든 스레드가 작업을 마칠 때까지 대기
        executorService.shutdown();

        int leftStockQuantity = productService.getProductInfo(productId).productStocks()
                .stream().mapToInt(ProductStockResponse::stockQuantity).sum();
        Assertions.assertThat(leftStockQuantity).isEqualTo(0);

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
