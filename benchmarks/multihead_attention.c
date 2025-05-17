#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>

#define BATCH 1
#define SEQ_LEN 4
#define D_MODEL 8
#define NUM_HEADS 2
#define D_HEAD (D_MODEL / NUM_HEADS)

float randf() {
    return ((float)rand()) / RAND_MAX;
}

void softmax(float* scores, int len) {
    float max = scores[0];
    for (int i = 1; i < len; ++i)
        if (scores[i] > max) max = scores[i];
    
    float sum = 0.0;
    for (int i = 0; i < len; ++i) {
        scores[i] = expf(scores[i] - max);
        sum += scores[i];
    }

    for (int i = 0; i < len; ++i)
        scores[i] /= sum;
}

void dot_product_attention(float* Q, float* K, float* V, float* output, int len, int d) {
    float scores[SEQ_LEN];
    for (int i = 0; i < len; ++i) {
        scores[i] = 0;
        for (int j = 0; j < d; ++j)
            scores[i] += Q[j] * K[i * d + j];
        scores[i] /= sqrtf(d);
    }

    softmax(scores, len);

    for (int j = 0; j < d; ++j) {
        output[j] = 0;
        for (int i = 0; i < len; ++i)
            output[j] += scores[i] * V[i * d + j];
    }
}

int main() {
    srand(time(NULL));
    float Q[SEQ_LEN * D_MODEL], K[SEQ_LEN * D_MODEL], V[SEQ_LEN * D_MODEL];
    float output[SEQ_LEN * D_MODEL] = {0};

    for (int i = 0; i < SEQ_LEN * D_MODEL; ++i)
        Q[i] = K[i] = V[i] = randf();

    for (int h = 0; h < NUM_HEADS; ++h) {
        for (int i = 0; i < SEQ_LEN; ++i) {
            float* q = &Q[i * D_MODEL + h * D_HEAD];
            float* out = &output[i * D_MODEL + h * D_HEAD];
            dot_product_attention(q, &K[h * D_HEAD], &V[h * D_HEAD], out, SEQ_LEN, D_HEAD);
        }
    }

    printf("Multihead Attention Output:\n");
    for (int i = 0; i < SEQ_LEN * D_MODEL; ++i)
        printf("%.2f%c", output[i], (i + 1) % D_MODEL == 0 ? '\n' : ' ');
    return 0;
}
