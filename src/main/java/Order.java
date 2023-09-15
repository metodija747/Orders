public class Order {
    private String email;
    private String name;
    private String surname;
    private String address;
    private String telNumber;
    private String orderListStr;
    private Double totalPrice;

    // Gett  ers
    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getSurname() {
        return surname;
    }

    public String getAddress() {
        return address;
    }

    public String getTelNumber() {
        return telNumber;
    }

    public String getOrderListStr() {
        return orderListStr;
    }

    public Double getTotalPrice() {
        return totalPrice;
    }

    // Setters
    public void setEmail(String email) {
        this.email = email;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setTelNumber(String telNumber) {
        this.telNumber = telNumber;
    }

    public void setOrderListStr(String orderListStr) {
        this.orderListStr = orderListStr;
    }

    public void setTotalPrice(Double totalPrice) {
        this.totalPrice = totalPrice;
    }
}
