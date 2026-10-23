package com.recordandgrow.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FallbackGraphStoreTest {

    private final GraphStore primary = mock(GraphStore.class);
    private final GraphStore fallback = mock(GraphStore.class);
    private final FallbackGraphStore store = new FallbackGraphStore(primary, fallback, "neo4j");

    @Test
    void searchShouldFallbackWhenPrimaryFails() {
        when(primary.search(anyList(), anyInt())).thenThrow(new GraphStoreException("down"));
        when(fallback.search(anyList(), anyInt())).thenReturn(List.of(
                new GraphHit(1L, "A", "包含", "B", 1L, "t")));

        assertThat(store.search(List.of("a"), 5)).singleElement()
                .satisfies(hit -> assertThat(hit.source()).isEqualTo("A"));
    }

    @Test
    void replaceShouldFallbackWhenPrimaryFails() {
        org.mockito.Mockito.doThrow(new GraphStoreException("down"))
                .when(primary).replaceDocument(eq(1L), eq("标题"), anyList());
        store.replaceDocument(1L, "标题", List.of(new GraphTriple("A", "包含", "B")));

        verify(fallback).replaceDocument(eq(1L), eq("标题"), anyList());
    }

    @Test
    void bothFailingShouldPropagate() {
        when(primary.search(anyList(), anyInt())).thenThrow(new GraphStoreException("down"));
        when(fallback.search(anyList(), anyInt())).thenThrow(new GraphStoreException("pg down"));

        assertThatThrownBy(() -> store.search(List.of("a"), 5))
                .isInstanceOf(GraphStoreException.class)
                .hasMessageContaining("pg down");
    }

    @Test
    void countsShouldFallback() {
        when(primary.countNodes()).thenThrow(new IllegalStateException("down"));
        when(fallback.countNodes()).thenReturn(9L);

        assertThat(store.countNodes()).isEqualTo(9L);
    }
}
