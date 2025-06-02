package com.project.shopapp.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.javafaker.Faker;
import com.project.shopapp.components.LocalizationUtils;
import com.project.shopapp.dtos.ProductDTO;
import com.project.shopapp.dtos.ProductImageDTO;
import com.project.shopapp.models.Product;
import com.project.shopapp.models.ProductImage;
import com.project.shopapp.responses.product.ProductListResponse;
import com.project.shopapp.responses.product.ProductResponse;
import com.project.shopapp.services.product.IProductRedisService;
import com.project.shopapp.services.product.IProductService;
import com.project.shopapp.utils.MessageKeys;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.nio.file.Paths;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Sử dụng MockitoExtension để khởi tạo @Mock và @InjectMocks
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private IProductService productService;

    @Mock
    private IProductRedisService productRedisService;

    @Mock
    private LocalizationUtils localizationUtils;

    // Bắt BindingResult để test validation error
    @Mock
    private BindingResult bindingResult;

    @InjectMocks
    private ProductController productController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Khởi tạo MockMvc để test một số endpoint GET riêng biệt (viewImage, getProducts, ...)
        mockMvc = MockMvcBuilders.standaloneSetup(productController).build();
    }

    // === 1. Test createProduct(...) ===

    @Test
    @DisplayName("createProduct: khi dữ liệu hợp lệ thì trả về Product và HTTP 200")
    void testCreateProduct_Success() throws Exception {
        // 1. Chuẩn bị DTO và Product giả
        ProductDTO dto = ProductDTO.builder()
                .name("Test Product")
                .price(1000f)
                .description("Desc")
                .thumbnail("thumb.png")
                .categoryId(1L)
                .build();

        Product savedProduct = new Product();
        savedProduct.setId(42L);
        savedProduct.setName("Test Product");
        savedProduct.setPrice(1000f);
        savedProduct.setDescription("Desc");

        // 2. Không có lỗi validation
        when(bindingResult.hasErrors()).thenReturn(false);
        when(productService.createProduct(dto)).thenReturn(savedProduct);

        // 3. Gọi method
        ResponseEntity<?> response = productController.createProduct(dto, bindingResult);

        // 4. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(savedProduct);

        // 5. Verify productService.createProduct(...) được gọi đúng tham số
        verify(productService, times(1)).createProduct(dto);
    }

    @Test
    @DisplayName("createProduct: khi có lỗi validate, trả về HTTP 400 với danh sách message")
    void testCreateProduct_ValidationErrors() {
        // 1. Chuẩn bị BindingResult trả về lỗi
        when(bindingResult.hasErrors()).thenReturn(true);

        List<FieldError> fieldErrors = List.of(
                new FieldError("productDTO", "name", "Name không được để trống"),
                new FieldError("productDTO", "price", "Price phải > 0")
        );
        when(bindingResult.getFieldErrors()).thenReturn(fieldErrors);

        // 2. Gọi method
        ProductDTO dto = new ProductDTO(); // Dữ liệu không quan trọng, vì bindingResult.hasErrors()=true
        ResponseEntity<?> response = productController.createProduct(dto, bindingResult);

        // 3. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Object body = response.getBody();
        assertThat(body).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) body;
        assertThat(errors).containsExactly("Name không được để trống", "Price phải > 0");

        // 4. Đảm bảo productService.createProduct(...) không được gọi
        verify(productService, never()).createProduct(any());
    }

    @Test
    @DisplayName("createProduct: khi productService ném exception, trả về HTTP 400 và message exception")
    void testCreateProduct_ServiceThrows() throws Exception {
        ProductDTO dto = ProductDTO.builder().name("P").price(10f).build();
        when(bindingResult.hasErrors()).thenReturn(false);

        when(productService.createProduct(dto))
                .thenThrow(new RuntimeException("Lỗi tạo product"));

        ResponseEntity<?> response = productController.createProduct(dto, bindingResult);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Lỗi tạo product");
    }

    // === 2. Test uploadImages(...) ===

    @Test
    @DisplayName("uploadImages: khi số file > MAXIMUM_IMAGES_PER_PRODUCT, trả về 400 và thông báo từ localizationUtils")
    void testUploadImages_TooManyFiles() {
        // 1. Mock product tồn tại
        Product fakeProduct = new Product();
        fakeProduct.setId(10L);
        when(productService.getProductById(10L)).thenReturn(fakeProduct);

        // 2. Chuẩn bị list MultipartFile có kích thước quá lớn
        List<MockMultipartFile> files = new ArrayList<>();
        for (int i = 0; i < ProductImage.MAXIMUM_IMAGES_PER_PRODUCT + 1; i++) {
            files.add(new MockMultipartFile("file", ("file" + i + ".jpg").getBytes()));
        }

        when(localizationUtils.getLocalizedMessage(MessageKeys.UPLOAD_IMAGES_MAX_5))
                .thenReturn("Chỉ được upload tối đa 5 ảnh");

        // 3. Gọi phương thức
        ResponseEntity<?> response = productController.uploadImages(10L, new ArrayList<>(files));

        // 4. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Chỉ được upload tối đa 5 ảnh");
    }

    @Test
    @DisplayName("uploadImages: khi file > 10MB, trả về 413 Payload Too Large")
    void testUploadImages_FileTooLarge() throws Exception {
        // 1. Mock product tồn tại
        Product fakeProduct = new Product();
        fakeProduct.setId(99L);
        when(productService.getProductById(99L)).thenReturn(fakeProduct);

        // 2. Tạo MultipartFile giả có size > 10MB
        byte[] largeContent = new byte[11 * 1024 * 1024]; // 11MB
        MockMultipartFile largeFile = new MockMultipartFile(
                "file", "big.jpg", "image/jpeg", largeContent
        );

        when(localizationUtils.getLocalizedMessage(MessageKeys.UPLOAD_IMAGES_FILE_LARGE))
                .thenReturn("Kích thước file vượt quá 10MB");

        // 3. Gọi method
        List<MockMultipartFile> files = List.of(largeFile);
        ResponseEntity<?> response = productController.uploadImages(99L, new ArrayList<>(files));

        // 4. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isEqualTo("Kích thước file vượt quá 10MB");
    }

    @Test
    @DisplayName("uploadImages: khi file không phải ảnh, trả về 415 Unsupported Media Type")
    void testUploadImages_FileNotImage() throws Exception {
        // 1. Mock product tồn tại
        Product fakeProduct = new Product();
        fakeProduct.setId(55L);
        when(productService.getProductById(55L)).thenReturn(fakeProduct);

        // 2. Tạo MultipartFile có content-type không phải image
        byte[] content = "dummy".getBytes();
        MockMultipartFile txtFile = new MockMultipartFile(
                "file", "doc.txt", "text/plain", content
        );

        when(localizationUtils.getLocalizedMessage(MessageKeys.UPLOAD_IMAGES_FILE_MUST_BE_IMAGE))
                .thenReturn("File phải là định dạng ảnh");

        // 3. Gọi method
        List<MockMultipartFile> files = List.of(txtFile);
        ResponseEntity<?> response = productController.uploadImages(55L, new ArrayList<>(files));

        // 4. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isEqualTo("File phải là định dạng ảnh");
    }

    @Test
    @DisplayName("uploadImages: khi upload thành công, trả về danh sách ProductImage và HTTP 200")
    void testUploadImages_Success() throws Exception {
        // 1. Mock product tồn tại
        Product fakeProduct = new Product();
        fakeProduct.setId(77L);
        when(productService.getProductById(77L)).thenReturn(fakeProduct);

        // 2. Tạo MultipartFile hợp lệ (size nhỏ hơn 10MB và contentType image)
        byte[] content = "hello".getBytes();
        MockMultipartFile imageFile = new MockMultipartFile(
                "file", "img.png", "image/png", content
        );

        // 3. Mock productService.storeFile(...) trả về tên file đã lưu
        when(productService.storeFile(imageFile)).thenReturn("img_stored.png");

        // 4. Mock productService.createProductImage(...) trả về ProductImage
        ProductImageDTO imageDTO = ProductImageDTO.builder()
                .imageUrl("img_stored.png")
                .build();
        ProductImage fakeImage = ProductImage.builder()
                .id(123L)
                .imageUrl("img_stored.png")
                .product(fakeProduct)
                .build();
        when(productService.createProductImage(77L, imageDTO)).thenReturn(fakeImage);

        // 5. Gọi method
        List<MockMultipartFile> files = List.of(imageFile);
        ResponseEntity<?> response = productController.uploadImages(77L, new ArrayList<>(files));

        // 6. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<ProductImage> resultList = (List<ProductImage>) response.getBody();
        assertThat(resultList).hasSize(1);
        assertThat(resultList.get(0).getImageUrl()).isEqualTo("img_stored.png");

        // 7. Verify service được gọi đúng tham số
        verify(productService, times(1)).storeFile(imageFile);
        verify(productService, times(1)).createProductImage(eq(77L), any(ProductImageDTO.class));
    }

    // === 3. Test viewImage(...) ===

    @Test
    @DisplayName("viewImage: khi Paths.get ném exception, trả về 404 Not Found")
    void testViewImage_ExceptionPath() throws Exception {
        String imageName = "any.png";

        // Mock Paths.get(...) để ném exception
        try (MockedStatic<Paths> mockedPaths = Mockito.mockStatic(Paths.class)) {
            mockedPaths.when(() -> Paths.get("uploads/" + imageName))
                    .thenThrow(new RuntimeException("File system error"));

            ResponseEntity<?> response = productController.viewImage(imageName);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("viewImage: khi file tồn tại hoặc fallback, trả về 200 OK và content type IMAGE_JPEG")
    void testViewImage_FallbackOrExists() throws Exception {
        // Trường hợp này chúng ta không mock Paths.get, để Paths.get tạo UrlResource.
        // Giả sử file "uploads/nonexistent.jpg" không tồn tại, nhưng controller sẽ trả về fallback "notfound.jpeg"
        ResponseEntity<?> response = productController.viewImage("nonexistent.jpg");

        // Dù file không tồn tại, controller vẫn trả UrlResource (fallback). Chỉ kiểm tra status và header.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getBody()).isInstanceOf(UrlResource.class);
    }

    // === 4. Test getProducts(...) ===

    @Test
    @DisplayName("getProducts: cache hit -> trả về danh sách ProductResponse từ Redis")
    void testGetProducts_CacheHit() throws JsonProcessingException {
        // 1. Chuẩn bị mock Redis trả về list đã có sẵn
        ProductResponse pr1 = new ProductResponse();
        pr1.setId(1L);
        pr1.setName("P1");
        pr1.setTotalPages(5);

        List<ProductResponse> cachedList = List.of(pr1);
        when(productRedisService.getAllProducts(anyString(), anyLong(), any(PageRequest.class)))
                .thenReturn(cachedList);

        // 2. Gọi method
        ResponseEntity<ProductListResponse> response = productController.getProducts("abc", 2L, 0, 10);

        // 3. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProductListResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalPages()).isEqualTo(5);
        assertThat(body.getProducts()).hasSize(1);
        assertThat(body.getProducts().get(0).getName()).isEqualTo("P1");

        // 4. Không gọi đến productService.getAllProducts(...) khi cache hit
        verify(productService, never()).getAllProducts(anyString(), anyLong(), any(PageRequest.class));
    }

    @Test
    @DisplayName("getProducts: cache miss -> gọi productService, saveAllProducts, trả về kết quả")
    void testGetProducts_CacheMiss() throws JsonProcessingException {
        // 1. cache miss
        when(productRedisService.getAllProducts(anyString(), anyLong(), any(PageRequest.class)))
                .thenReturn(null);

        // 2. Chuẩn bị Page<ProductResponse> giả
        ProductResponse pr = new ProductResponse();
        pr.setId(2L);
        pr.setName("P2");
        pr.setTotalPages(3);

        List<ProductResponse> content = List.of(pr);
        Page<ProductResponse> pageResult = new PageImpl<>(content, PageRequest.of(0,10), 3);

        when(productService.getAllProducts(eq(""), eq(0L), any(PageRequest.class)))
                .thenReturn(pageResult);

        // 3. Gọi method
        ResponseEntity<ProductListResponse> response = productController.getProducts("", 0L, 0, 10);

        // 4. Kiểm tra
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProductListResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalPages()).isEqualTo(3);
        assertThat(body.getProducts()).hasSize(1);
        assertThat(body.getProducts().get(0).getName()).isEqualTo("P2");
        // ProductResponse trong content phải có totalPages = 3
        assertThat(body.getProducts().get(0).getTotalPages()).isEqualTo(3);

        // 5. Verify saveAllProducts được gọi với đúng tham số
        verify(productRedisService, times(1)).saveAllProducts(eq(content), eq(""), eq(0L), any(PageRequest.class));
    }

    // === 5. Test getProductById(...) ===

    @Test
    @DisplayName("getProductById: success -> trả về ProductResponse và HTTP 200")
    void testGetProductById_Success() {
        Product fakeProduct = new Product();
        fakeProduct.setId(100L);
        fakeProduct.setName("Prod100");
        when(productService.getProductById(100L)).thenReturn(fakeProduct);

        ResponseEntity<?> response = productController.getProductById(100L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object body = response.getBody();
        assertThat(body).isInstanceOf(ProductResponse.class);

        ProductResponse pr = (ProductResponse) body;
        assertThat(pr.getId()).isEqualTo(100L);
        assertThat(pr.getName()).isEqualTo("Prod100");
    }

    @Test
    @DisplayName("getProductById: service throws -> trả về HTTP 400 và message")
    void testGetProductById_Exception() {
        when(productService.getProductById(999L))
                .thenThrow(new RuntimeException("Không tìm thấy sản phẩm"));

        ResponseEntity<?> response = productController.getProductById(999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Không tìm thấy sản phẩm");
    }

    // === 6. Test getProductsByIds(...) ===

    @Test
    @DisplayName("getProductsByIds: success -> trả về danh sách Product và HTTP 200")
    void testGetProductsByIds_Success() {
        // Chuỗi ids = "1,2,3"
        List<Product> fakeList = List.of(new Product(), new Product(), new Product());
        when(productService.findProductsByIds(List.of(1L, 2L, 3L)))
                .thenReturn(fakeList);

        ResponseEntity<?> response = productController.getProductsByIds("1,2,3");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object body = response.getBody();
        assertThat(body).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Product> resultList = (List<Product>) body;
        assertThat(resultList).hasSize(3);
    }

    @Test
    @DisplayName("getProductsByIds: service throws -> trả về HTTP 400")
    void testGetProductsByIds_Exception() {
        when(productService.findProductsByIds(anyList()))
                .thenThrow(new RuntimeException("Lỗi truy vấn"));

        ResponseEntity<?> response = productController.getProductsByIds("5,6");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Lỗi truy vấn");
    }

    // === 7. Test deleteProduct(...) ===

    @Test
    @DisplayName("deleteProduct: success -> trả về 200 và message thành công")
    void testDeleteProduct_Success() {
        // Giả sử productService.deleteProduct(...) không ném exception
        doNothing().when(productService).deleteProduct(50L);

        ResponseEntity<String> response = productController.deleteProduct(50L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Product with id = 50 deleted successfully");
    }

    @Test
    @DisplayName("deleteProduct: service throws -> trả về 400 Bad Request")
    void testDeleteProduct_Exception() {
        doThrow(new RuntimeException("Xóa thất bại")).when(productService).deleteProduct(51L);

        ResponseEntity<String> response = productController.deleteProduct(51L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Xóa thất bại");
    }

    // === 8. Test updateProduct(...) ===

    @Test
    @DisplayName("updateProduct: success -> trả về Product updated và HTTP 200")
    void testUpdateProduct_Success() {
        ProductDTO dto = ProductDTO.builder().name("NewName").price(500f).build();
        Product updated = new Product();
        updated.setId(60L);
        updated.setName("NewName");
        updated.setPrice(500f);

        when(productService.updateProduct(60L, dto)).thenReturn(updated);

        ResponseEntity<?> response = productController.updateProduct(60L, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(updated);
    }

    @Test
    @DisplayName("updateProduct: service throws -> trả về HTTP 400 Bad Request")
    void testUpdateProduct_Exception() {
        ProductDTO dto = ProductDTO.builder().name("X").price(0f).build();
        when(productService.updateProduct(61L, dto))
                .thenThrow(new RuntimeException("Cập nhật thất bại"));

        ResponseEntity<?> response = productController.updateProduct(61L, dto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Cập nhật thất bại");
    }
}
