from main import greet, add, subtract


class TestGreet:
    def test_greet_basic(self):
        assert greet("World") == "Hello, World!"

    def test_greet_empty_string(self):
        assert greet("") == "Hello, !"

    def test_greet_name(self):
        assert greet("Alice") == "Hello, Alice!"


class TestAdd:
    def test_add_positive(self):
        assert add(2, 3) == 5

    def test_add_negative(self):
        assert add(-1, -2) == -3

    def test_add_zero(self):
        assert add(0, 0) == 0

    def test_add_mixed(self):
        assert add(-1, 5) == 4


class TestSubtract:
    def test_subtract_positive(self):
        assert subtract(5, 3) == 2

    def test_subtract_negative(self):
        assert subtract(-1, -2) == 1

    def test_subtract_zero(self):
        assert subtract(0, 0) == 0

    def test_subtract_mixed(self):
        assert subtract(-1, 5) == -6
