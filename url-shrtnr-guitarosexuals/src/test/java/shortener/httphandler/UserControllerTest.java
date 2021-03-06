package shortener.httphandler;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.RxHttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import javax.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import shortener.httphandler.utils.JsonResponse;
import org.mockito.Mockito;
import shortener.database.entities.User;
import shortener.database.entities.UserSession;
import shortener.exceptions.database.NotFound;
import shortener.exceptions.database.UniqueViolation;
import shortener.users.UserRepository;
import shortener.users.UserSessionRepository;
import shortener.users.protection.HashFunction;

@MicronautTest
@TestInstance(Lifecycle.PER_CLASS)
public class UserControllerTest {

  @Inject
  EmbeddedServer embeddedServer;
  @Inject
  @Client("/")
  RxHttpClient client;
  @Inject
  UserRepository userRepository;
  @Inject
  UserSessionRepository userSessionRepository;

  @MockBean(UserRepository.class)
  public UserRepository mockUserRepository() {
    return Mockito.mock(UserRepository.class);
  }

  @MockBean(UserSessionRepository.class)
  public UserSessionRepository mockUserSessionRepository() {
    return Mockito.mock(UserSessionRepository.class);
  }

  @BeforeEach
  void mockAuthData() {
    var testUser =
        new User(1L, "test@mail.com", HashFunction.hashOut("CoolPasswd123", "test@mail.com"));

    Mockito.when(userRepository.getByEmail(Mockito.anyString())).thenReturn(testUser);

    Mockito.when(userSessionRepository.get(Mockito.anyString()))
        .thenReturn(new UserSession(1L, "token"));
  }

  @Test
  void signUpWithValidData() {
    Mockito.when(userRepository.create(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(new User(2L, "newuser@mail.com", "Passwd2021"));

    var userData = new UserData("newuser@mail.com", "Passwd2021");

    HttpRequest<UserData> request = HttpRequest.POST("/users/signup", userData);
    HttpResponse<String> response = client.toBlocking().exchange(request);

    assertThat((CharSequence) response.getStatus()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void signUpEmptyUser() {
    Mockito.when(userRepository.create(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(new User(2L, "newuser@mail.com", "Passwd2021"));

    var emptyUser = new UserData("", "");

    HttpRequest<UserData> emptyUserRequest = HttpRequest
        .POST("/users/signup", emptyUser);

    // TODO: improve 4xx code response checking (e.g. status code check)
    Throwable emptyUserException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            emptyUserRequest,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );

    String jsonResponse = JsonResponse.getErrorMessage(
        0,
        "Credentials should not be empty."
    );

    assertThat(emptyUserException.getMessage()).contains(jsonResponse);
  }

  @Test
  void signUpWithWrongEmail() {
    var wrongMailUser = new UserData("wrongmail", "Passwd2021");

    HttpRequest<UserData> wrongMailUserRequest = HttpRequest
        .POST("/users/signup", wrongMailUser);

    Throwable wrongMailUserException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            wrongMailUserRequest,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );
    assertThat(wrongMailUserException.getMessage()).contains("Invalid email address");

  }

  @Test
  void signUpExistingUser() {
    Mockito.when(userRepository.create(Mockito.anyString(), Mockito.anyString()))
        .thenThrow(new UniqueViolation("users"));

    var existingUser = new UserData("test@mail.com", "Passwd123");

    HttpRequest<UserData> emptyUserRequest = HttpRequest
        .POST("/users/signup", existingUser);

    // TODO: improve 4xx code response checking (e.g. status code check)
    Throwable emptyUserException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            emptyUserRequest,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );

    String jsonResponse = JsonResponse.getErrorMessage(
        2,
        String.format("User %s has already been registered.", existingUser.email())
    );

    Assertions.assertEquals(emptyUserException.getMessage(), jsonResponse);
  }

  @Test
  void signUpWithWeakPassword() {
    var wrongPasswordUser = new UserData("user@mail.com", "pass");

    HttpRequest<UserData> wrongPasswordUserRequest = HttpRequest
        .POST("/users/signup", wrongPasswordUser);

    Throwable wrongPasswordUserException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            wrongPasswordUserRequest,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );

    String jsonResponse = JsonResponse.getErrorMessage(
        0,
        "Password must be at least 8 characters long."
    );

    assertThat(wrongPasswordUserException.getMessage()).contains(jsonResponse);
  }

  @Test
  void signInWithCorrectCredentials() {
    var user = new UserData("test@mail.com", "CoolPasswd123");

    HttpRequest<UserData> request = HttpRequest
        .POST("/users/signin", user);

    HttpResponse<String> response = client.toBlocking().exchange(
        request,
        String.class
    );

    assertThat((CharSequence) response.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(response.body()).isNotNull();
    assertThat(response.body()).contains("token");
  }

  @Test
  void signInWithWrongPassword() {
    var wrongPasswdUser = new UserData("test@mail.com", "WrongPasswd");

    HttpRequest<UserData> request = HttpRequest
        .POST("/users/signin", wrongPasswdUser);

    Throwable wrongPasswordUserException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            request,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );
    assertThat(wrongPasswordUserException.getMessage()).contains("Credentials Do Not Match");
  }

  @Test
  void signOutWithAuthorization() {
    var validUser = new UserData("test@mail.com", "CoolPasswd123");

    HttpRequest<UserData> signInRequest = HttpRequest.POST("/users/signin", validUser);
    String signInResponseBody = client.toBlocking().retrieve(signInRequest);

    final String accessToken = JsonParser.parseString(signInResponseBody)
        .getAsJsonObject().get("token").getAsString();

    HttpRequest<Object> signOutRequest = HttpRequest.GET("/users/signout")
        .header(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", accessToken));

    HttpResponse<String> response = client.toBlocking().exchange(
        signOutRequest,
        String.class
    );

    assertThat((CharSequence) response.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(response.body()).isNotNull();
    assertThat(response.body()).contains("Successfully signed out");
  }

  @Test
  void signInWithNonExistentEmail() {
    Mockito.when(userRepository.getByEmail(Mockito.anyString()))
        .thenThrow(new NotFound("users", "nonexistent@mail.com"));

    var wrongPasswdUser = new UserData("nonexistent@mail.com", "Passwd123");

    HttpRequest<UserData> request = HttpRequest
        .POST("/users/signin", wrongPasswdUser);

    Throwable wrongPasswordUserException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            request,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );
    assertThat(wrongPasswordUserException.getMessage()).contains("User Not Found");
  }

  @Test
  void signOutWithoutAuthorization() {
    HttpRequest<Object> request = HttpRequest.GET("/users/signout");

    Throwable userNotAuthorizedException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            request,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );

    assertThat(userNotAuthorizedException.getMessage()).contains("Unauthorized");
  }

  @Test
  void signOutWithInvalidToken() {
    HttpRequest<Object> request = HttpRequest.GET("/users/signout")
        .header(HttpHeaders.AUTHORIZATION, "Bearer invalidTokenEntry");

    Throwable userNotAuthorizedException = Assertions.assertThrows(
        HttpClientResponseException.class,
        () -> client.toBlocking().exchange(
            request,
            Argument.of(String.class),
            Argument.of(String.class)
        )
    );

    assertThat(userNotAuthorizedException.getMessage()).contains("Unauthorized");
  }

  @Test
  void signUpAndSignInScenario() {
    var testUser =
        new User(1L, "anotheruser@mail.com",
            HashFunction.hashOut("CoolPasswd123", "anotheruser@mail.com"));

    Mockito.when(userRepository.getByEmail(Mockito.anyString())).thenReturn(testUser);

    Mockito.when(userSessionRepository.get(Mockito.anyString()))
        .thenReturn(new UserSession(1L, "token"));

    Mockito.when(userRepository.create("anotheruser@mail.com", "CoolPasswd123"))
        .thenReturn(testUser);

    var user = new UserData("anotheruser@mail.com", "CoolPasswd123");

    HttpRequest<UserData> signUpRequest = HttpRequest.POST("/users/signup", user);
    HttpResponse<String> signUpResponse = client.toBlocking().exchange(signUpRequest);

    HttpRequest<UserData> signInRequest = HttpRequest
        .POST("/users/signin", user);

    HttpResponse<String> signInResponse = client.toBlocking().exchange(
        signInRequest,
        String.class
    );

    assertThat((CharSequence) signUpResponse.getStatus()).isEqualTo(HttpStatus.CREATED);
    assertThat((CharSequence) signInResponse.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(signInResponse.body()).isNotNull();
    assertThat(signInResponse.body()).contains("token");
  }
}
