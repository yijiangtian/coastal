package arrays;

public class Array04 {

	public static void main(String[] args) {
		char[] a = new char[2];
		a[0] = 0;
		a[1] = 0;
		test(a);
	}

	public static int test(char[] arr) {
		if (arr[0] < arr[1]) {
			return -1;
		} else if (arr[0] > arr[1]) {
			return 1;
		} else {
			return 0;
		}
	}
	
}
