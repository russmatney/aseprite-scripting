app.transaction(
  "main", function()
    for k, t in pairs(app.params) do
      print("argument key:", k, "value:", t)
    end
end)
