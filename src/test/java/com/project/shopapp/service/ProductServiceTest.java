package com.project.shopapp.services.product;

import com.project.shopapp.dtos.ProductDTO;
import com.project.shopapp.dtos.ProductImageDTO;
import com.project.shopapp.exceptions.DataNotFoundException;
import com.project.shopapp.exceptions.InvalidParamException;
import com.project.shopapp.models.Category;
import com.project.shopapp.models.Product;
import com.project.shopapp.models.ProductImage;
import com.project.shopapp.repositories.CategoryRepository;
import com.project.shopapp.repositories.ProductImageRepository;
import com.project.shopapp.repositories.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductServiceTest {

    @InjectMocks
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductImageRepository productImageRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreateProduct_Success() throws Exception {
        ProductDTO dto = new ProductDTO();
        dto.setName("Test Product");
        dto.setPrice(100);
        dto.setThumbnail("thumb.jpg");
        dto.setDescription("Description");
        dto.setCategoryId(1L);

        Category category = new Category();
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));

        Product savedProduct = Product.builder().name("Test Product").build();
        when(productRepository.save(any(Product.class))).thenReturn(savedProduct);

        Product result = productService.createProduct(dto);

        assertEquals("Test Product", result.getName());
    }

    @Test
    void testCreateProduct_CategoryNotFound() {
        ProductDTO dto = new ProductDTO();
        dto.setCategoryId(2L);
        when(categoryRepository.findById(2L)).thenReturn(Optional.empty());

        assertThrows(DataNotFoundException.class, () -> productService.createProduct(dto));
    }

    @Test
    void testGetProductById_Success() throws Exception {
        Product product = new Product();
        product.setId(1L);
        when(productRepository.getDetailProduct(1L)).thenReturn(Optional.of(product));

        Product result = productService.getProductById(1L);
        assertEquals(1L, result.getId());
    }

    @Test
    void testGetProductById_NotFound() {
        when(productRepository.getDetailProduct(1L)).thenReturn(Optional.empty());

        assertThrows(DataNotFoundException.class, () -> productService.getProductById(1L));
    }

    @Test
    void testUpdateProduct_Success() throws Exception {
        Product product = new Product();
        product.setId(1L);
        product.setName("Old Name");

        Category category = new Category();

        ProductDTO dto = new ProductDTO();
        dto.setName("New Name");
        dto.setCategoryId(1L);
        dto.setPrice(123);
        dto.setDescription("Updated Desc");

        when(productRepository.getDetailProduct(1L)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        Product updated = productService.updateProduct(1L, dto);
        assertEquals("New Name", updated.getName());
        assertEquals("Updated Desc", updated.getDescription());
    }

    @Test
    void testDeleteProduct_Exists() {
        Product product = new Product();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        productService.deleteProduct(1L);
        verify(productRepository, times(1)).delete(product);
    }

    @Test
    void testDeleteProduct_NotExists() {
        when(productRepository.findById(1L)).thenReturn(Optional.empty());

        productService.deleteProduct(1L);
        verify(productRepository, never()).delete(any());
    }

    @Test
    void testExistsByName() {
        when(productRepository.existsByName("Test")).thenReturn(true);
        assertTrue(productService.existsByName("Test"));
    }

    @Test
    void testCreateProductImage_Success() throws Exception {
        Product product = new Product();
        product.setId(1L);
        product.setThumbnail(null);

        ProductImageDTO dto = new ProductImageDTO();
        dto.setImageUrl("img.jpg");
        dto.setProductId(1L);

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductId(1L)).thenReturn(new ArrayList<>());

        ProductImage image = new ProductImage();
        image.setImageUrl("img.jpg");
        when(productImageRepository.save(any(ProductImage.class))).thenReturn(image);

        ProductImage result = productService.createProductImage(1L, dto);
        assertEquals("img.jpg", result.getImageUrl());
        assertEquals("img.jpg", product.getThumbnail());
    }

    @Test
    void testCreateProductImage_TooManyImages() {
        Product product = new Product();
        product.setId(1L);
        List<ProductImage> existingImages = Arrays.asList(
                new ProductImage(), new ProductImage(),
                new ProductImage(), new ProductImage(),
                new ProductImage()
        );

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productImageRepository.findByProductId(1L)).thenReturn(existingImages);

        ProductImageDTO dto = new ProductImageDTO();
        dto.setImageUrl("too-many.jpg");
        dto.setProductId(1L);

        assertThrows(InvalidParamException.class,
                () -> productService.createProductImage(1L, dto));
    }

    @Test
    void testStoreFile_Success() throws IOException {
        byte[] fileBytes = "image content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "image", "test.jpg", "image/jpeg", fileBytes);

        String result = productService.storeFile(file);

        assertTrue(result.endsWith(".jpg"));
    }

    @Test
    void testStoreFile_InvalidFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "invalid".getBytes());

        assertThrows(IOException.class, () -> productService.storeFile(file));
    }
}
