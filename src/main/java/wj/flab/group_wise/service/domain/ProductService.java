package wj.flab.group_wise.service.domain;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wj.flab.group_wise.domain.exception.EntityNotFoundException;
import wj.flab.group_wise.domain.exception.TargetEntity;
import wj.flab.group_wise.domain.product.Product;
import wj.flab.group_wise.domain.product.ProductStock;
import wj.flab.group_wise.domain.product.ProductViewResponseMapper;
import wj.flab.group_wise.dto.product.request.ProductCreateRequest;
import wj.flab.group_wise.dto.product.request.ProductDetailUpdateRequest;
import wj.flab.group_wise.dto.product.request.ProductStockAddRequest;
import wj.flab.group_wise.dto.product.request.ProductStockAddRequest.StockAddRequest;
import wj.flab.group_wise.dto.product.request.ProductStockSetRequest;
import wj.flab.group_wise.dto.product.response.ProductViewResponse;
import wj.flab.group_wise.repository.ProductRepository;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductValidator productValidator;
    private final ProductViewResponseMapper productViewResponseMapper;

    // 조회용. 캐시에 있으면 메서드를 아예 실행 안하고 캐시값을 반환. 없으면 실행하고 결과를 캐시에 저장.
    @Cacheable(value = "products", key = "#productId")
    public ProductViewResponse getProductInfo(Long productId) {
        Product product = findProductById(productId);
        return productViewResponseMapper.mapToProductViewResponse(product);
    }

    public Long createProduct(ProductCreateRequest productToCreate) {
        Product product = processCreateProduct(productToCreate);
        productRepository.save(product);
        return product.getId();
    }

    private Product processCreateProduct(ProductCreateRequest productToCreate) {
        Product product = productToCreate.toEntity();
        productValidator.validateAddProduct(product);
        product.appendProductAttributes(productToCreate.attributes());
        return product;
    }

    // 캐시 삭제. 데이터가 바뀌어서 캐시가 더 이상 유효하지 않으므로 삭제. 다음 조회 때 다시 캐시 생성.
    @CacheEvict(value = "products", key = "#productId")
    public void setProductStock(Long productId, ProductStockSetRequest productToSetStock) {
        Product product = findProductById(productId);
        productValidator.validateProductLifeCycleBeforeMajorUpdate(product);
        product.setProductStocks(productToSetStock.stockQuantitySetRequests());
        product.deleteProductStocks(productToSetStock.stockDeleteRequests());
    }

    @CacheEvict(value = "products", key = "#productId")
    public void addProductStock(Long productId, ProductStockAddRequest productToAddStock) {
        Product product = findProductById(productId);
        List<StockAddRequest> stockAddRequests = productToAddStock.stockAddRequests();
        product.addProductStocks(stockAddRequests);
    }

    @CacheEvict(value = "products", key = "#productId")
    public void updateProductDetails(Long productId, ProductDetailUpdateRequest productToUpdate) {
        Product product = findProductById(productId);
        productValidator.validateProductLifeCycleBeforeMajorUpdate(product);

        product.updateProductBasicInfo(
            productToUpdate.seller(),
            productToUpdate.productName(),
            productToUpdate.basePrice(),
            productToUpdate.saleStatus());

        product.restructureAttributes(productToUpdate);
    }

    @Transactional(readOnly = true)
    public Product findProductById(Long productId) {
        return productRepository.findById(productId)
            .orElseThrow(() -> new EntityNotFoundException(TargetEntity.PRODUCT, productId));
    }

    @Transactional(readOnly = true)
    public Product findProductByProductStockId(Long productStockId) {
        return productRepository.findByProductStockId(productStockId)
            .orElseThrow(() -> new EntityNotFoundException(TargetEntity.PRODUCT,
                String.format("productStockId 가 %d 인 상품이 존재하지 않습니다.", productStockId)));
    }

    @CacheEvict(value = "products", key = "#productId")
    public void deleteProduct(Long productId) {
        Product product = findProductById(productId);
        productValidator.validateProductLifeCycleBeforeMajorUpdate(product);
        productRepository.delete(product);
    }

    public int getCheapestStockPrice(Long productId) {
        Product product = findProductById(productId);
        return product.getProductStocks().stream()
            .mapToInt(ProductStock::getPrice)
            .min()
            .orElse(0);
    }
}
