package com.example.project.repository;

import com.example.project.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Integer> {

    /**
     * Loads every product together with the relations needed by the product list
     * (type / producer) so the listing screen avoids lazy N+1 lookups.
     */
    @Query("""
       select distinct p
       from Product p
       left join fetch p.typeID
       left join fetch p.producerID
       order by p.name asc
       """)
    List<Product> findAllWithRelations();

    /** Loads a single product with the relations needed by the Product Detail header. */
    @Query("""
       select p
       from Product p
       left join fetch p.typeID
       left join fetch p.producerID
       where p.productID = :productId
       """)
    Optional<Product> findDetailById(@Param("productId") Integer productId);

    /** Product code is unique; used to reject duplicates when creating a product. */
    boolean existsByCode(String code);

    /** Barcode is unique when present; used to reject duplicates when creating a product. */
    boolean existsByBarcode(String barcode);

    /** Barcode uniqueness check for editing: true if some OTHER product already has this barcode. */
    @Query("select count(p) > 0 from Product p where p.barcode = :barcode and p.productID <> :productId")
    boolean existsByBarcodeExcludingProduct(@Param("barcode") String barcode, @Param("productId") Integer productId);

    /**
     * Product name must be unique.
     *
     * <p>The comparison is deliberately left to the column's collation,
     * {@code utf8mb4_0900_ai_ci} — accent-insensitive and case-insensitive — so "Paracetamol",
     * "paracetamol" and "Páracetamol" all count as the same name without any {@code LOWER()} or
     * unaccenting in the query. That is what a pharmacist means by "trùng tên". <strong>It is a
     * property of the schema, not of this code</strong>: on a case-sensitive collation these
     * checks would silently narrow to exact matches.</p>
     */
    boolean existsByName(String name);

    /** Name uniqueness for editing: true if some OTHER product already has this name. */
    @Query("select count(p) > 0 from Product p where p.name = :name and p.productID <> :productId")
    boolean existsByNameExcludingProduct(@Param("name") String name, @Param("productId") Integer productId);

    /** Registration number is unique <em>when present</em>; blank means "not declared", never a duplicate. */
    boolean existsByRegistrationNumber(String registrationNumber);

    /** Registration-number uniqueness for editing: true if some OTHER product already has it. */
    @Query("""
       select count(p) > 0
       from Product p
       where p.registrationNumber = :registrationNumber and p.productID <> :productId
       """)
    boolean existsByRegistrationNumberExcludingProduct(@Param("registrationNumber") String registrationNumber,
                                                        @Param("productId") Integer productId);

    /**
     * Highest numeric suffix among internal codes of the form {@code SP<digits>} (0 if none).
     * Used to auto-generate the next internal product code. MySQL-specific (REGEXP + CAST).
     */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(code, 3) AS UNSIGNED)), 0) "
            + "FROM product WHERE code REGEXP '^SP[0-9]+$'", nativeQuery = true)
    long findMaxProductCodeSequence();

    /**
     * Per-product VAT rate suggestion source: (productID, vatRateOverride, type.defaultVATRate).
     * Used to pre-fill the per-line "Thuế suất VAT" field on Purchase Invoice creation —
     * vatRateOverride wins when set, else the product's Type.defaultVATRate.
     */
    @Query("select p.productID, p.vatRateOverride, t.defaultVATRate from Product p left join p.typeID t")
    List<Object[]> findVatRateSuggestions();
}