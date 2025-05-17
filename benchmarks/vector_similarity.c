#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>

#define NUM_VECS 5
#define DIM 8

float randf() {
    return ((float)rand()) / RAND_MAX;
}

float cosine_similarity(float* a, float* b, int dim) {
    float dot = 0, norm_a = 0, norm_b = 0;
    for (int i = 0; i < dim; ++i) {
        dot += a[i] * b[i];
        norm_a += a[i] * a[i];
        norm_b += b[i] * b[i];
    }
    return dot / (sqrtf(norm_a) * sqrtf(norm_b) + 1e-6);
}

int main() {
    srand(time(NULL));
    float db[NUM_VECS][DIM], query[DIM];

    for (int i = 0; i < NUM_VECS; ++i)
        for (int j = 0; j < DIM; ++j)
            db[i][j] = randf();

    for (int j = 0; j < DIM; ++j)
        query[j] = randf();

    int best = -1;
    float best_sim = -1;

    for (int i = 0; i < NUM_VECS; ++i) {
        float sim = cosine_similarity(query, db[i], DIM);
        if (sim > best_sim) {
            best_sim = sim;
            best = i;
        }
    }

    printf("Most similar vector index: %d (cosine similarity: %.3f)\n", best, best_sim);
    return 0;
}
