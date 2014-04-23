package uk.co.thomseddon.relay;

/**
 * Created by thom on 4/11/14.
 */
public class Client {

    String address;
    String name;
    int port;

    public Client(String name, String address, int port) {
        this.name = name;
        this.address = address;
        this.port = port;
    }
}
