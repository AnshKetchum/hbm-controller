#include <stdio.h>
#include <stdlib.h>

int main() {
    int* data = malloc(5 * sizeof(int));
    for (int i = 0; i < 5; i++) {
        data[i] = i * 10;       // WRITE
    }

    for (int i = 0; i < 5; i++) {
        printf("%d\n", data[i]); // READ
    }

    free(data);
    return 0;
}
