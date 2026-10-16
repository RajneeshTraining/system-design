package com.example.bloom.web;

import com.example.bloom.domain.User;
import com.example.bloom.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@Validated
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    public record CreateUserRequest(@NotBlank @Email String email, @NotBlank String name) { }

    public record UserResponse(Long id, String email, String name) {
        static UserResponse of(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getName());
        }
    }

    /** GET /api/users?email=jane@example.com  -> 200 + user, or 404 */
    @GetMapping
    public ResponseEntity<UserResponse> findByEmail(@RequestParam @NotBlank String email) {
        return service.findByEmail(email)
                .map(u -> ResponseEntity.ok(UserResponse.of(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return UserResponse.of(service.create(req.email(), req.name()));
    }

    @ExceptionHandler(UserService.DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String duplicate(UserService.DuplicateEmailException e) {
        return e.getMessage();
    }
}
