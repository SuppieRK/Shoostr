export const routeExample = appRegistrationExample();

function appRegistrationExample() {
    return `import io.github.suppierk.shoostr.Shoostr;

public class App {
  public static void main(String[] args) {
    new Shoostr()
      .routes(routes ->
        routes.get("/hello", (req, res) -> res.text("Hi!"))
      )
      .start();
  }
}`;
}
