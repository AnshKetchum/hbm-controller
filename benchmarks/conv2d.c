#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define IN_H 5
#define IN_W 5
#define K 3
#define OUT_H (IN_H - K + 1)
#define OUT_W (IN_W - K + 1)

float randf() {
    return ((float)rand()) / RAND_MAX;
}

int main() {
    srand(time(NULL));
    float input[IN_H][IN_W], kernel[K][K], output[OUT_H][OUT_W];

    for (int i = 0; i < IN_H; ++i)
        for (int j = 0; j < IN_W; ++j)
            input[i][j] = randf();

    for (int i = 0; i < K; ++i)
        for (int j = 0; j < K; ++j)
            kernel[i][j] = randf();

    for (int i = 0; i < OUT_H; ++i)
        for (int j = 0; j < OUT_W; ++j) {
            output[i][j] = 0;
            for (int ki = 0; ki < K; ++ki)
                for (int kj = 0; kj < K; ++kj)
                    output[i][j] += input[i + ki][j + kj] * kernel[ki][kj];
        }

    printf("2D Convolution Output:\n");
    for (int i = 0; i < OUT_H; ++i) {
        for (int j = 0; j < OUT_W; ++j)
            printf("%.2f ", output[i][j]);
        printf("\n");
    }

    return 0;
}
