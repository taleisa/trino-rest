package io.trino.plugin.rest;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.trino.plugin.rest.openapi.PostBodyDefinition;
import io.trino.plugin.rest.openapi.PostFilterDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PostBodyDefinitionTest {

  // A scalar (non-array, isArray=false) template slot - e.g. "category":
  // "{{category}}" - is
  // written into the request body template because the target endpoint's schema
  // declares a
  // single string. Trino's DomainTranslator normalizes both `IN (...)` and `col =
  // a OR col = b`
  // into the same Domain.multipleValues(...) shape, so RestMetadata.applyFilter
  // can still
  // resolve more than one value for it. A scalar field can only ever hold one
  // value in a single
  // request, so build() must reject that case rather than silently drop values or
  // smuggle them
  // in as a JSON array the target schema never declared for this field.
  private static final String EXPECTED_MESSAGE = "Filter category resolved to 2 values but its request body field only accepts a single "
      + "value; IN/OR predicates producing more than one value are not supported for this filter";

  private PostBodyDefinition scalarCategoryFilter() {
    PostFilterDefinition category = new PostFilterDefinition("category", "VARCHAR", true, false, List.of("category"));
    return new PostBodyDefinition(Map.of("category", "{{category}}"), List.of(category));
  }

  @Test
  void inPredicateWithMultipleValuesOnScalarFilterIsRejected() {
    // WHERE request_filter_category IN ('electronics', 'furniture')
    PostBodyDefinition postBody = scalarCategoryFilter();
    Map<String, List<String>> resolved = Map.of("category", List.of("electronics", "furniture"));

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> postBody.buildPostPayload(resolved));

    assertEquals(EXPECTED_MESSAGE, exception.getMessage());
  }

  @Test
  void sameScalarFilterUsedTwiceWithOrIsRejected() {
    // WHERE request_filter_category = 'electronics' OR request_filter_category =
    // 'furniture'
    // Indistinguishable from the IN case above by the time it reaches build(): both
    // resolve to
    // the same two-element list for the "category" filter.
    PostBodyDefinition postBody = scalarCategoryFilter();
    Map<String, List<String>> resolved = Map.of("category", List.of("electronics", "furniture"));

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> postBody.buildPostPayload(resolved));

    assertEquals(EXPECTED_MESSAGE, exception.getMessage());
  }

  @Test
  void inPredicateWithMultipleValuesOnArrayFilterKeepsAllValues() {
    // WHERE request_filter_ids IN (1, 2, 3) - an actual array-declared filter
    // (isArray=true)
    // must keep every value, unlike the scalar case above.
    PostFilterDefinition ids = new PostFilterDefinition("ids", "BIGINT", false, true, List.of("ids"));
    PostBodyDefinition postBody = new PostBodyDefinition(Map.of("ids", List.of("{{ids}}")), List.of(ids));
    Map<String, List<String>> resolved = Map.of("ids", List.of("1", "2", "3"));

    Map<String, Object> body = postBody.buildPostPayload(resolved);

    assertEquals("{\"ids\":[1,2,3]}", PostBodyDefinition.serialize(body));
  }
}
